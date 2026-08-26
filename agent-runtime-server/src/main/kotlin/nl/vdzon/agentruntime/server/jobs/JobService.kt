package nl.vdzon.agentruntime.server.jobs

import com.fasterxml.jackson.databind.JsonNode
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RequestIdentity
import nl.vdzon.agentruntime.server.config.RuntimeEnvironment
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.util.Base64

@Service
class JobService(
    private val store: JobStore,
    private val attachments: InputAttachmentStore,
    private val properties: RuntimeProperties,
    private val schemaValidator: JsonResultValidator,
) {
    @Transactional
    fun create(identity: RequestIdentity, request: CreateJobRequest): JobView {
        val tenant = identity.tenantId ?: throw ApiException("FORBIDDEN", "Consumer identity required.", HttpStatus.FORBIDDEN)
        validatePolicy(tenant, request)
        schemaValidator.validateSchema(request.responseSchema)
        val decodedAttachments = validateAttachments(request)
        store.findByIdempotency(tenant, request.idempotencyKey)?.let { existing ->
            if (existing.request != request) throw ApiException("IDEMPOTENCY_CONFLICT", "The idempotency key already belongs to a different request.", HttpStatus.CONFLICT)
            return existing.view
        }
        return try {
            val stored = store.insert(tenant, request, properties.maxAttempts, properties.defaultPriority)
            decodedAttachments.forEach { (attachment, content) -> attachments.insert(stored.view.id, attachment.filename, attachment.mimeType, content) }
            stored.view
        } catch (_: DuplicateKeyException) {
            val existing = store.findByIdempotency(tenant, request.idempotencyKey)
                ?: throw ApiException("IDEMPOTENCY_CONFLICT", "Concurrent request could not be resolved.", HttpStatus.CONFLICT)
            if (existing.request != request) throw ApiException("IDEMPOTENCY_CONFLICT", "The idempotency key already belongs to a different request.", HttpStatus.CONFLICT)
            existing.view
        }
    }

    fun visibleJob(identity: RequestIdentity, id: String): StoredJob {
        val job = store.find(id) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        if (identity.role.name != "ADMIN" && job.view.tenantId != identity.tenantId) {
            throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        }
        return job
    }

    fun result(identity: RequestIdentity, id: String): JobResultView {
        val job = visibleJob(identity, id)
        if (job.view.status != JobStatus.SUCCEEDED || job.result == null || job.completedAt == null) {
            throw ApiException("RESULT_NOT_AVAILABLE", "No successful immutable result is available yet.", HttpStatus.CONFLICT)
        }
        return JobResultView(id, job.result, job.usage, store.artifacts(id), job.completedAt)
    }

    fun validateResult(job: StoredJob, result: JsonNode) {
        job.request.responseSchema?.let { schema ->
            val errors = schemaValidator.validate(schema, result)
            if (errors.isNotEmpty()) throw ApiException("RESULT_SCHEMA_INVALID", errors.joinToString("; ") { "${it.path}: ${it.message}" })
        }
    }

    private fun validatePolicy(tenant: String, request: CreateJobRequest) {
        if (properties.environment == RuntimeEnvironment.PRODUCTION && request.provider == Provider.MOCKED) {
            throw ApiException("MOCKED_FORBIDDEN", "MOCKED is never accepted in production.")
        }
        requireRequest(request.provider.name in properties.allowedProviders(tenant), "Provider is not allowed for this consumer.")
        requireRequest(properties.modelAllowed(tenant, request.model), "Model is not allowed for this consumer.")
        when (tenant) {
            "product-factory" -> {
                requireRequest(request.jobKind == JobKind.APPLICATION_WORK, "Product Factory may only request APPLICATION_WORK.")
            }
            "software-factory" -> {
                requireRequest(request.jobKind == JobKind.REPOSITORY_WORK, "Software Factory may only request REPOSITORY_WORK.")
            }
            else -> throw ApiException("TENANT_FORBIDDEN", "Tenant is not configured.", HttpStatus.FORBIDDEN)
        }
        requireRequest(request.environmentKeys.size == request.environmentKeys.toSet().size, "Environment keys must be unique.")
        val prefixes = properties.allowedEnvironmentPrefixes(tenant)
        request.environmentKeys.forEach { key ->
            requireRequest(ENVIRONMENT_KEY.matches(key), "Invalid environment key name.")
            requireRequest(key.substringBefore("__") in prefixes, "Environment key prefix is not allowed for this consumer.")
        }
        if (request.jobKind == JobKind.APPLICATION_WORK) {
            requireRequest(request.repositoryRequest == null, "APPLICATION_WORK cannot publish repository changes.")
            request.repositorySnapshot?.let {
                val uri = runCatching { URI(it.url) }.getOrNull()
                requireRequest(uri?.scheme == "https" && uri.host?.lowercase() == "github.com", "Repository snapshots must use public github.com HTTPS URLs.")
            }
        } else {
            requireRequest(request.repositoryRequest != null, "REPOSITORY_WORK requires repositoryRequest.")
            requireRequest(request.repositorySnapshot == null, "REPOSITORY_WORK resolves its repository through the alias.")
            requireRequest(request.repositoryRequest?.alias in setOf("software-factory", "agent-runtime", "test-repository"), "Repository alias is not allowed.")
        }
    }

    private fun requireRequest(condition: Boolean, message: String) {
        if (!condition) throw ApiException("POLICY_VIOLATION", message)
    }

    private fun validateAttachments(request: CreateJobRequest): List<Pair<InputAttachmentRequest, ByteArray>> {
        requireRequest(request.attachments.map { it.filename }.toSet().size == request.attachments.size, "Attachment filenames must be unique.")
        var total = 0L
        return request.attachments.map { attachment ->
            requireRequest(SAFE_FILENAME.matches(attachment.filename) && attachment.filename !in setOf(".", ".."), "Attachment filename is unsafe.")
            requireRequest(attachment.mimeType in ALLOWED_INPUT_MIME_TYPES, "Attachment MIME type is not allowed.")
            val content = try { Base64.getDecoder().decode(attachment.contentBase64) }
            catch (_: IllegalArgumentException) { throw ApiException("ATTACHMENT_INVALID_BASE64", "Attachment content is not valid Base64.") }
            if (content.size > properties.inputAttachmentMaxBytes) throw ApiException("ATTACHMENT_TOO_LARGE", "Attachment exceeds the per-file limit.", HttpStatus.PAYLOAD_TOO_LARGE)
            total += content.size
            if (total > properties.jobInputAttachmentMaxBytes) throw ApiException("JOB_ATTACHMENT_LIMIT", "Attachments exceed the per-job limit.", HttpStatus.PAYLOAD_TOO_LARGE)
            requireRequest(matchesMagicBytes(attachment.mimeType, content), "Attachment content does not match its MIME type.")
            attachment to content
        }
    }

    private fun matchesMagicBytes(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP"
        "application/pdf" -> bytes.size >= 5 && String(bytes, 0, 5) == "%PDF-"
        else -> true
    }

    companion object {
        private val ENVIRONMENT_KEY = Regex("[A-Z][A-Z0-9_]*__[A-Z][A-Z0-9_]*")
        private val SAFE_FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
        private val ALLOWED_INPUT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain", "application/json")
    }
}
