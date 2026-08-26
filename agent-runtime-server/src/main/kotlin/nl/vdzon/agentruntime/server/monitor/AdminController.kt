package nl.vdzon.agentruntime.server.monitor

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.config.*
import nl.vdzon.agentruntime.server.jobs.*
import nl.vdzon.agentruntime.server.workers.*
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

data class ManagementJobItem(
    val id: String, val technicalName: String, val application: String, val jobKind: JobKind,
    val provider: Provider, val model: String, val status: JobStatus, val phase: String,
    val workerId: String?, val progressPercent: Int?, val progressMessage: String?, val waitingReason: String?,
    val promptPreview: String, val outputPreview: String?, val inputAttachmentCount: Int, val artifactCount: Int,
    val createdAt: Instant, val updatedAt: Instant, val completedAt: Instant?,
)
data class ManagementList<T>(val serverTime: Instant, val items: List<T>, val nextCursor: String? = null, val previousCursor: String? = null)
data class ManagementEnvironment(val serverTime: Instant, val environment: String, val version: String = "0.1.0")
data class ManagementWorker(val worker: WorkerView, val activeJobs: Int, val currentTechnicalName: String?)
data class ManagementOutputAttempt(
    val id: String, val number: Int, val status: String, val provider: String, val model: String,
    val validationErrors: List<JsonValidationError>, val diagnosticExcerpt: String?,
    val startedAt: Instant, val completedAt: Instant?,
)
data class ManagementInputAttachment(
    val id: String, val filename: String, val mimeType: String, val sizeBytes: Long,
    val sha256: String, val createdAt: Instant,
)
data class ManagementJobDetail(
    val serverTime: Instant, val job: ManagementJobItem, val prompt: String,
    val inputAttachments: List<ManagementInputAttachment>, val responseSchema: com.fasterxml.jackson.databind.JsonNode?,
    val result: JobResultView?, val errorCode: String?, val errorMessage: String?, val events: List<JobEventView>,
    val attempts: List<AttemptSummary>, val outputAttempts: List<ManagementOutputAttempt>,
    val cancelledAt: Instant?, val cancelledBy: String?,
)

@RestController
@RequestMapping("/v1/management")
class AdminController(
    private val jobs: JobStore,
    private val workers: WorkerStore,
    private val outputs: OutputAttemptService,
    private val transcripts: TranscriptStore,
    private val attachments: InputAttachmentStore,
    private val properties: RuntimeProperties,
) {
    @GetMapping("/environment")
    fun environment(request: HttpServletRequest): ManagementEnvironment {
        admin(request)
        return ManagementEnvironment(Instant.now(), if (properties.environment == RuntimeEnvironment.PRODUCTION) "Productie" else "Acceptatie")
    }

    @GetMapping("/jobs/running")
    fun running(request: HttpServletRequest): ManagementList<ManagementJobItem> {
        admin(request)
        return ManagementList(Instant.now(), jobs.list(null, JobStatus.RUNNING, 500).map(::item))
    }

    @GetMapping("/queue")
    fun queue(request: HttpServletRequest): ManagementList<ManagementJobItem> {
        admin(request)
        val online = workers.listWorkers().filter { it.status == "ONLINE" }
        val waiting = jobs.list(null, null, 500).filter { it.view.status in setOf(JobStatus.QUEUED, JobStatus.WAITING_FOR_WORKER) }
            .sortedWith(compareByDescending<StoredJob> { it.view.priority }.thenBy { it.view.createdAt })
            .map { job -> item(job, waitReason(job, online)) }
        return ManagementList(Instant.now(), waiting)
    }

    @GetMapping("/jobs/completed")
    fun completed(
        @RequestParam(defaultValue = "") search: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") limit: Int,
        request: HttpServletRequest,
    ): ManagementList<ManagementJobItem> {
        admin(request)
        val bounded = limit.coerceIn(1, 30)
        val all = jobs.list(null, null, 5000)
            .filter { it.view.status in setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED) }
            .filter { search.isBlank() || listOf(it.view.id, technicalName(it), it.view.tenantId).any { value -> value.contains(search, true) } }
            .sortedWith(compareByDescending<StoredJob> { it.completedAt ?: it.view.updatedAt }.thenByDescending { it.view.id })
        val offset = decodeCursor(cursor).coerceIn(0, all.size)
        val page = all.drop(offset).take(bounded).map(::item)
        return ManagementList(
            Instant.now(), page,
            (offset + page.size).takeIf { it < all.size }?.let(::encodeCursor),
            (offset - bounded).coerceAtLeast(0).takeIf { offset > 0 }?.let(::encodeCursor),
        )
    }

    @GetMapping("/jobs/{id}")
    fun detail(@PathVariable id: String, request: HttpServletRequest): ManagementJobDetail {
        admin(request)
        val job = jobs.find(id) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        val result = job.result?.let { JobResultView(id, it, job.usage, jobs.artifacts(id), job.completedAt ?: job.view.updatedAt) }
        return ManagementJobDetail(
            Instant.now(), item(job), job.request.prompt, attachments.list(id).map(::attachmentItem), job.request.responseSchema,
            result, job.view.errorCode, job.view.errorMessage,
            jobs.events(id), workers.attemptsForJob(id), outputs.list(id).map {
                ManagementOutputAttempt(it.id, it.number, it.status, it.provider, it.model, it.validationErrors, it.diagnosticExcerpt, it.startedAt, it.completedAt)
            }, job.cancelledAt, job.cancelledBy,
        )
    }

    @GetMapping("/jobs/{jobId}/attachments/{attachmentId}")
    fun attachment(
        @PathVariable jobId: String,
        @PathVariable attachmentId: String,
        request: HttpServletRequest,
    ): ResponseEntity<ByteArrayResource> {
        admin(request)
        jobs.find(jobId) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        val attachment = attachments.find(attachmentId)?.takeIf { it.jobId == jobId }
            ?: throw ApiException("ATTACHMENT_NOT_FOUND", "Attachment not found.", HttpStatus.NOT_FOUND)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(attachment.mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${attachment.filename.replace("\"", "")}\"")
            .body(ByteArrayResource(attachment.content))
    }

    @GetMapping("/jobs/{id}/result")
    fun result(@PathVariable id: String, request: HttpServletRequest): JobResultView {
        admin(request)
        val job = jobs.find(id) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        val result = job.result ?: throw ApiException("RESULT_NOT_AVAILABLE", "Result not available.", HttpStatus.CONFLICT)
        return JobResultView(id, result, job.usage, jobs.artifacts(id), job.completedAt ?: job.view.updatedAt)
    }

    @GetMapping("/jobs/{id}/transcript")
    fun transcript(
        @PathVariable id: String,
        @RequestParam(required = false) afterSequence: Long?,
        @RequestParam(required = false) beforeSequence: Long?,
        @RequestParam(defaultValue = "100") limit: Int,
        request: HttpServletRequest,
    ): TranscriptPage {
        admin(request)
        val job = jobs.find(id) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        val items = transcripts.page(id, afterSequence, beforeSequence, limit)
        return TranscriptPage(items, items.lastOrNull()?.sequence, job.view.status == JobStatus.RUNNING)
    }

    @GetMapping("/workers")
    fun workerList(request: HttpServletRequest): ManagementList<ManagementWorker> {
        admin(request)
        val active = jobs.list(null, JobStatus.RUNNING, 500)
        val items = workers.listWorkers().map { worker ->
            val workerJobs = active.filter { workers.activeForJob(it.view.id)?.workerId == worker.workerId }
            ManagementWorker(worker, workerJobs.size, workerJobs.firstOrNull()?.let(::technicalName))
        }
        return ManagementList(Instant.now(), items)
    }

    @GetMapping("/summary")
    fun summary(request: HttpServletRequest): Map<String, Any> {
        admin(request)
        val all = jobs.list(null, null, 5000)
        return mapOf("serverTime" to Instant.now(), "jobsByStatus" to JobStatus.entries.associateWith { status -> all.count { it.view.status == status } })
    }

    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun retry(@PathVariable id: String, request: HttpServletRequest) {
        admin(request)
        val job = jobs.find(id) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        if (job.view.status !in setOf(JobStatus.FAILED, JobStatus.CANCELLED)) throw ApiException("RETRY_NOT_ALLOWED", "Only failed or cancelled jobs can be retried.", HttpStatus.CONFLICT)
        jobs.resetForAdminRetry(id)
    }

    private fun item(job: StoredJob, waitingReason: String? = null): ManagementJobItem {
        val active = workers.activeForJob(job.view.id)
        return ManagementJobItem(
            job.view.id, technicalName(job), job.view.tenantId, job.view.jobKind, job.view.provider, job.view.model,
            job.view.status, job.view.phase, active?.workerId, job.view.progressPercent, job.view.progressMessage,
            waitingReason, preview(job.request.prompt), job.result?.toString()?.let(::preview),
            attachments.count(job.view.id), jobs.artifactCount(job.view.id),
            job.view.createdAt, job.view.updatedAt, job.completedAt,
        )
    }

    private fun attachmentItem(value: StoredInputAttachment) = ManagementInputAttachment(
        value.id, value.filename, value.mimeType, value.sizeBytes, value.sha256, value.createdAt,
    )

    private fun preview(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(PREVIEW_CHARACTERS)

    private fun waitReason(job: StoredJob, online: List<WorkerView>): String = when {
        job.view.notBefore?.isAfter(Instant.now()) == true -> "uitgesteld tot retrymoment"
        online.any { worker ->
            job.view.provider in worker.providers &&
                (worker.models.isEmpty() || job.view.model in worker.models) &&
                (if (job.view.jobKind == JobKind.APPLICATION_WORK) "application-work" else "repository-work") in worker.capabilities &&
                job.request.environmentKeys.all { it in worker.availableEnvironmentKeys }
        } -> "klaar om te claimen"
        else -> "wacht op geschikte worker"
    }

    private fun technicalName(job: StoredJob) = "${job.view.tenantId}-${job.view.jobKind.name.lowercase().replace('_', '-')}-${job.view.id.take(8)}"
    private fun encodeCursor(offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(offset.toString().toByteArray(StandardCharsets.UTF_8))
    private fun decodeCursor(cursor: String?): Int = runCatching { String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).toInt() }.getOrDefault(0)
    private fun admin(request: HttpServletRequest) {
        if (ApiSecurity.identity(request).role != PrincipalRole.ADMIN) throw ApiException("FORBIDDEN", "Administrator required.", HttpStatus.FORBIDDEN)
    }

    companion object { const val PREVIEW_CHARACTERS = 240 }
}

@RestController
class HealthController {
    @GetMapping("/healthz") fun health() = mapOf("status" to "UP")
}
