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
import java.net.URI

@Service
class JobService(
    private val store: JobStore,
    private val properties: RuntimeProperties,
    private val schemaValidator: SimpleJsonSchemaValidator,
) {
    fun create(identity: RequestIdentity, request: CreateJobRequest): JobView {
        val tenant = identity.tenantId ?: throw ApiException("FORBIDDEN", "Consumer identity required.", HttpStatus.FORBIDDEN)
        validateProfile(tenant, request)
        store.findByIdempotency(tenant, request.idempotencyKey)?.let { existing ->
            if (existing.request != request) throw ApiException("IDEMPOTENCY_CONFLICT", "The idempotency key already belongs to a different request.", HttpStatus.CONFLICT)
            return existing.view
        }
        return try {
            store.insert(tenant, request).view
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
            if (errors.isNotEmpty()) throw ApiException("RESULT_SCHEMA_INVALID", errors.joinToString("; "))
        }
    }

    private fun validateProfile(tenant: String, request: CreateJobRequest) {
        if (properties.environment == RuntimeEnvironment.PRODUCTION && request.provider == Provider.MOCKED) {
            throw ApiException("MOCKED_FORBIDDEN", "MOCKED is never accepted in production.")
        }
        when (tenant) {
            "product-factory" -> {
                requireRequest(request.jobKind == JobKind.APPLICATION_WORK, "Product Factory may only request APPLICATION_WORK.")
                requireRequest(request.jobProfile in setOf("product-factory-default", "product-factory-browser", "product-factory-build"), "Unknown Product Factory profile.")
            }
            "software-factory" -> {
                requireRequest(request.jobKind == JobKind.REPOSITORY_WORK, "Software Factory may only request REPOSITORY_WORK.")
                requireRequest(request.jobProfile in setOf("software-factory-default", "software-factory-test"), "Unknown Software Factory profile.")
            }
            else -> throw ApiException("TENANT_FORBIDDEN", "Tenant is not configured.", HttpStatus.FORBIDDEN)
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
        val allowedResources = when (request.jobProfile) {
            "product-factory-default" -> setOf("web-read")
            "product-factory-browser" -> setOf("web-read", "browser")
            "product-factory-build" -> setOf("web-read", "browser", "build-test")
            "software-factory-default" -> setOf("build-test")
            "software-factory-test" -> setOf("build-test", "browser", "docker-test")
            else -> emptySet()
        }
        requireRequest(request.resourceRequests.all { it.key in allowedResources }, "One or more resources are not allowed by the job profile.")
    }

    private fun requireRequest(condition: Boolean, message: String) {
        if (!condition) throw ApiException("PROFILE_VIOLATION", message)
    }
}

@Service
class SimpleJsonSchemaValidator {
    fun validate(schema: JsonNode, value: JsonNode): List<String> = buildList { check(schema, value, "$", this) }

    private fun check(schema: JsonNode, value: JsonNode, path: String, errors: MutableList<String>) {
        when (schema.path("type").asText()) {
            "object" -> if (!value.isObject) errors += "$path must be an object" else {
                schema.path("required").forEach { required ->
                    if (!value.has(required.asText())) errors += "$path.${required.asText()} is required"
                }
                schema.path("properties").fields().forEach { (name, childSchema) ->
                    value.get(name)?.let { check(childSchema, it, "$path.$name", errors) }
                }
            }
            "array" -> if (!value.isArray) errors += "$path must be an array" else value.forEachIndexed { index, child -> check(schema.path("items"), child, "$path[$index]", errors) }
            "string" -> if (!value.isTextual) errors += "$path must be a string"
            "integer" -> if (!value.isIntegralNumber) errors += "$path must be an integer"
            "number" -> if (!value.isNumber) errors += "$path must be a number"
            "boolean" -> if (!value.isBoolean) errors += "$path must be a boolean"
        }
        if (schema.has("enum") && schema.path("enum").none { it == value }) errors += "$path is not an allowed value"
    }
}
