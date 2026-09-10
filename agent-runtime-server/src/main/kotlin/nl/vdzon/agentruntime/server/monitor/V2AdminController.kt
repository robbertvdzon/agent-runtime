package nl.vdzon.agentruntime.server.monitor

import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.v2.AttemptView
import nl.vdzon.agentruntime.contracts.v2.CostValue
import nl.vdzon.agentruntime.contracts.v2.JobEventView
import nl.vdzon.agentruntime.contracts.v2.JobStatus
import nl.vdzon.agentruntime.contracts.v2.JobUsageSummary
import nl.vdzon.agentruntime.contracts.v2.UsageQuality
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import nl.vdzon.agentruntime.server.v2.RegisteredV2WorkerDetails
import nl.vdzon.agentruntime.server.v2.StoredV2Job
import nl.vdzon.agentruntime.server.v2.V2Download
import nl.vdzon.agentruntime.server.v2.V2JobStore
import nl.vdzon.agentruntime.server.v2.V2ObjectStore
import nl.vdzon.agentruntime.server.v2.V2UsageService
import nl.vdzon.agentruntime.server.v2.V2UsageStore
import nl.vdzon.agentruntime.server.v2.V2WorkerStore
import nl.vdzon.agentruntime.server.v2.requireAdmin
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

data class V2ManagementJobItem(
    val id: String,
    val technicalName: String,
    val application: String,
    val jobKind: String,
    val taskType: String,
    val provider: String,
    val model: String,
    val executionMode: String,
    val status: String,
    val phase: String,
    val workerId: String?,
    val progressPercent: Int?,
    val progressMessage: String?,
    val waitingReason: String?,
    val promptPreview: String,
    val outputPreview: String?,
    val inputAttachmentCount: Int,
    val artifactCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val startedAt: Instant?,
    val durationMillis: Long?,
    val costs: List<CostValue>,
    val costAvailable: Boolean,
)

data class V2ManagementObject(
    val id: String,
    val name: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Instant,
)

data class V2ManagementResult(
    val jobId: String,
    val result: JsonNode,
    val artifacts: List<V2ManagementObject>,
    val usageSummary: JobUsageSummary,
    val completedAt: Instant,
)

data class V2ManagementJobDetail(
    val serverTime: Instant,
    val job: V2ManagementJobItem,
    val prompt: String,
    val inputAttachments: List<V2ManagementObject>,
    val responseSchema: JsonNode?,
    val result: V2ManagementResult?,
    val errorCode: String?,
    val errorMessage: String?,
    val events: List<JobEventView>,
    val attempts: List<AttemptView>,
)

data class V2ManagementTranscriptPart(
    val jobId: String,
    val attemptId: String?,
    val partId: String,
    val sequence: Long,
    val createdAt: Instant,
    val kind: String,
    val text: String,
    val redacted: Boolean = false,
)

data class V2ManagementTranscriptPage(
    val items: List<V2ManagementTranscriptPart>,
    val nextSequence: Long?,
    val active: Boolean,
)

data class V2ManagementWorkerView(
    val workerId: String,
    val bootId: String,
    val status: String,
    val capabilities: Set<String>,
    val providers: Set<String>,
    val models: Set<String>,
    val availableEnvironmentKeys: Set<String>,
    val maxConcurrency: Int,
    val lastHeartbeatAt: Instant,
    val executors: Set<nl.vdzon.agentruntime.contracts.v2.ExecutorCapability>,
    val versions: Map<String, String>,
)

data class V2ManagementWorker(
    val worker: V2ManagementWorkerView,
    val activeJobs: Int,
    val currentTechnicalName: String?,
)

@RestController
@RequestMapping("/v2/management")
class V2AdminController(
    private val jobs: V2JobStore,
    private val objects: V2ObjectStore,
    private val usage: V2UsageStore,
    private val usageService: V2UsageService,
    private val workers: V2WorkerStore,
    private val download: V2Download,
    private val properties: RuntimeProperties,
) {
    @GetMapping("/jobs/running")
    fun running(request: HttpServletRequest): ManagementList<V2ManagementJobItem> {
        request.requireAdmin()
        return ManagementList(Instant.now(), jobs.managementList().filter { it.view.status == JobStatus.RUNNING }.map(::item))
    }

    @GetMapping("/queue")
    fun queue(request: HttpServletRequest): ManagementList<V2ManagementJobItem> {
        request.requireAdmin()
        val registered = workers.registeredWorkers()
        val waiting = jobs.managementList()
            .filter { it.view.status in setOf(JobStatus.QUEUED, JobStatus.WAITING_FOR_WORKER) }
            .sortedBy { it.view.createdAt }
            .map { item(it, waitingReason(it, registered)) }
        return ManagementList(Instant.now(), waiting)
    }

    @GetMapping("/jobs/completed")
    fun completed(
        @RequestParam(defaultValue = "") search: String,
        @RequestParam(defaultValue = "") title: String,
        @RequestParam(required = false) consumer: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) until: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") limit: Int,
        request: HttpServletRequest,
    ): ManagementList<V2ManagementJobItem> {
        request.requireAdmin()
        if (from != null && until != null && !until.isAfter(from)) {
            throw ApiException("INVALID_PERIOD", "until must be after from.")
        }
        val terminal = jobs.managementList().filter { it.view.status in TERMINAL_STATUSES }
        val consumers = (properties.consumerTokens().keys + terminal.map { it.view.tenantId }).distinct().sorted()
        val all = terminal
            .filter { consumer.isNullOrBlank() || it.view.tenantId == consumer }
            .filter { from == null || !it.view.createdAt.isBefore(from) }
            .filter { until == null || it.view.createdAt.isBefore(until) }
            .filter { job ->
                val query = title.ifBlank { search }
                query.isBlank() || listOf(job.view.id, technicalName(job), job.view.idempotencyKey, job.view.tenantId)
                    .any { it.contains(query, ignoreCase = true) }
            }
            .sortedWith(compareByDescending<StoredV2Job> { it.view.completedAt ?: it.view.updatedAt }.thenByDescending { it.view.id })
        val bounded = limit.coerceIn(1, 30)
        val offset = decodeCursor(cursor).coerceIn(0, all.size)
        val page = all.drop(offset).take(bounded).map(::item)
        return ManagementList(
            Instant.now(), page,
            (offset + page.size).takeIf { it < all.size }?.let(::encodeCursor),
            (offset - bounded).coerceAtLeast(0).takeIf { offset > 0 }?.let(::encodeCursor),
            consumers,
        )
    }

    @GetMapping("/jobs/{id}")
    fun detail(@PathVariable id: String, request: HttpServletRequest): V2ManagementJobDetail {
        request.requireAdmin()
        val job = requireJob(id)
        val inputObjects = objects.inputObjects(id).map { (name, value) -> objectItem(name, value) }
        val outputObjects = objects.outputObjects(id).map { (name, value) -> objectItem(name, value) }
        val usageSummary = usage.jobSummary(id)
        val completedAt = job.view.completedAt
        val result = if (job.view.status == JobStatus.SUCCEEDED && job.result != null && completedAt != null) {
            V2ManagementResult(id, job.result, outputObjects, usageSummary, completedAt)
        } else null
        val attempts = jobs.attempts(id).map { attempt ->
            val summary = usage.attemptSummary(attempt.view.id)
            attempt.view.copy(usageSummary = summary, usageQuality = summary.usageQuality)
        }
        return V2ManagementJobDetail(
            Instant.now(), item(job), job.request.input.instruction, inputObjects, job.request.output.resultSchema,
            result, job.view.errorCode, job.view.errorMessage, jobs.events(id, 0, 500), attempts,
        )
    }

    @GetMapping("/jobs/{jobId}/attachments/{objectId}")
    fun inputObject(
        @PathVariable jobId: String,
        @PathVariable objectId: String,
        @RequestHeader("Range", required = false) range: String?,
        request: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> = objectDownload(jobId, objectId, "INPUT", range, request)

    @GetMapping("/jobs/{jobId}/artifacts/{objectId}")
    fun outputObject(
        @PathVariable jobId: String,
        @PathVariable objectId: String,
        @RequestHeader("Range", required = false) range: String?,
        request: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> = objectDownload(jobId, objectId, "OUTPUT", range, request)

    @GetMapping("/jobs/{id}/transcript")
    fun transcript(
        @PathVariable id: String,
        @RequestParam(required = false) afterSequence: Long?,
        @RequestParam(defaultValue = "100") limit: Int,
        request: HttpServletRequest,
    ): V2ManagementTranscriptPage {
        request.requireAdmin()
        val job = requireJob(id)
        val events = jobs.events(id, afterSequence ?: 0, limit.coerceIn(1, 500))
        val parts = events.filter { it.logText != null }.map { event ->
            V2ManagementTranscriptPart(
                id, event.attemptIdOrNull(), event.sequence.toString(), event.sequence, event.createdAt,
                event.logKind?.name ?: "SYSTEM", event.logText!!,
            )
        }
        return V2ManagementTranscriptPage(parts, events.lastOrNull()?.sequence, job.view.status !in TERMINAL_STATUSES)
    }

    @GetMapping("/workers")
    fun workerList(request: HttpServletRequest): ManagementList<V2ManagementWorker> {
        request.requireAdmin()
        val active = jobs.managementList().filter { it.view.status == JobStatus.RUNNING }
        val activeAttempts = active.mapNotNull { job -> jobs.activeAttempt(job.view.id)?.let { job to it } }
        val values = workers.registeredWorkers().map { record ->
            val own = activeAttempts.filter { (_, attempt) -> attempt.workerId == record.view.workerId && attempt.workerBootId == record.view.bootId }
            V2ManagementWorker(workerView(record), own.size, own.firstOrNull()?.first?.let(::technicalName))
        }
        return ManagementList(Instant.now(), values)
    }

    @GetMapping("/consumers")
    fun consumers(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) until: Instant?,
        request: HttpServletRequest,
    ): ManagementConsumerOverview {
        request.requireAdmin()
        val periodUntil = until ?: Instant.now()
        val periodFrom = from ?: periodUntil.minus(30, ChronoUnit.DAYS)
        if (!periodUntil.isAfter(periodFrom)) throw ApiException("INVALID_PERIOD", "until must be after from.")
        val now = Instant.now()
        val all = jobs.managementList()
        val costsByConsumer = usageService.summary(periodFrom, periodUntil, null, setOf("TENANT"), emptyMap()).rows
            .associate { it.dimensions.getValue("tenantId") to it.costs }
        val consumerIds = (properties.consumerTokens().keys + all.map { it.view.tenantId }).distinct().sorted()
        val items = consumerIds.map { id ->
            val own = all.filter { it.view.tenantId == id }
            val period = own.filter { !it.view.createdAt.isBefore(periodFrom) && it.view.createdAt.isBefore(periodUntil) }
            ManagementConsumerItem(
                id, own.size.toLong(),
                own.count { !it.view.createdAt.isBefore(now.minus(24, ChronoUnit.HOURS)) }.toLong(),
                own.count { !it.view.createdAt.isBefore(now.minus(7, ChronoUnit.DAYS)) }.toLong(),
                own.count { !it.view.createdAt.isBefore(now.minus(30, ChronoUnit.DAYS)) }.toLong(),
                period.size.toLong(), aggregateCosts(costsByConsumer[id].orEmpty()), 0,
                own.groupBy { listOf(it.view.execution.vendorId, it.view.execution.model, it.view.execution.mode.name) }
                    .map { (key, values) -> ManagementModelUsage("v2", key[0], key[1], key[2], values.size.toLong()) }
                    .sortedWith(compareByDescending<ManagementModelUsage> { it.jobCount }.thenBy { it.vendorId }.thenBy { it.model }),
            )
        }
        return ManagementConsumerOverview(now, periodFrom, periodUntil, items)
    }

    private fun item(job: StoredV2Job, waitingReason: String? = null): V2ManagementJobItem {
        val attempts = jobs.attempts(job.view.id)
        val active = attempts.lastOrNull { it.view.status == nl.vdzon.agentruntime.contracts.v2.AttemptStatus.RUNNING }
        val startedAt = attempts.minOfOrNull { it.view.startedAt }
        val durationUntil = job.view.completedAt ?: if (job.view.status == JobStatus.RUNNING) Instant.now() else null
        val durationMillis = startedAt?.let { start -> durationUntil?.let { end -> Duration.between(start, end).toMillis().coerceAtLeast(0) } }
        val usageSummary = usage.jobSummary(job.view.id)
        return V2ManagementJobItem(
            job.view.id, technicalName(job), job.view.tenantId, job.view.jobKind.name, job.view.taskType.name,
            job.view.execution.vendorId, job.view.execution.model, job.view.execution.mode.name,
            job.view.status.name, job.view.phase, active?.workerId, job.view.progressPercent, job.view.progressMessage,
            waitingReason, preview(job.request.input.instruction), job.result?.toString()?.let(::preview),
            objects.inputObjects(job.view.id).size, objects.outputObjects(job.view.id).size,
            job.view.createdAt, job.view.updatedAt, job.view.completedAt, startedAt, durationMillis,
            usageSummary.costs, usageSummary.usageQuality != UsageQuality.UNAVAILABLE || usageSummary.costs.isNotEmpty(),
        )
    }

    private fun objectDownload(
        jobId: String,
        objectId: String,
        direction: String,
        range: String?,
        request: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> {
        request.requireAdmin()
        requireJob(jobId)
        val linked = if (direction == "INPUT") objects.inputObjects(jobId) else objects.outputObjects(jobId)
        val record = linked.firstOrNull { it.second.view.objectId == objectId }?.second
            ?: throw ApiException("NOT_FOUND", "Object not found.", HttpStatus.NOT_FOUND)
        return download.response(record, range)
    }

    private fun objectItem(name: String, value: nl.vdzon.agentruntime.server.v2.StoredV2Object) = V2ManagementObject(
        value.view.objectId, name, value.view.filename, value.view.mimeType, value.view.sizeBytes,
        value.view.sha256, value.view.createdAt,
    )

    private fun workerView(record: RegisteredV2WorkerDetails): V2ManagementWorkerView {
        val last = record.view.lastHeartbeatAt
        val status = when {
            last.isBefore(Instant.now().minusSeconds(120)) -> "OFFLINE"
            last.isBefore(Instant.now().minusSeconds(45)) -> "STALE"
            else -> "ONLINE"
        }
        return V2ManagementWorkerView(
            record.view.workerId, record.view.bootId, status,
            record.view.executors.flatMap { it.taskTypes }.map { it.name }.toSet(),
            record.view.executors.map { it.vendorId }.toSet(), record.view.executors.map { it.model }.toSet(),
            record.environmentKeys, record.view.maxConcurrency, last, record.view.executors, record.versions,
        )
    }

    private fun waitingReason(job: StoredV2Job, registered: List<RegisteredV2WorkerDetails>): String {
        if (job.view.phase == "RETRY_WAIT") return "wacht op retrymoment"
        val onlineSince = Instant.now().minusSeconds(properties.recoverySeconds)
        val matching = registered.any { worker ->
            !worker.view.lastHeartbeatAt.isBefore(onlineSince) &&
                worker.view.executors.any { executor ->
                    executor.vendorId == job.view.execution.vendorId && executor.model == job.view.execution.model &&
                        executor.mode == job.view.execution.mode && job.view.taskType in executor.taskTypes
                } && job.request.environmentKeys.all(worker.environmentKeys::contains)
        }
        return if (matching) "klaar om te claimen" else "wacht op geschikte worker"
    }

    private fun requireJob(id: String): StoredV2Job = jobs.find(id)
        ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)

    private fun JobEventView.attemptIdOrNull(): String? = jobs.attempts(jobId)
        .firstOrNull { attempt ->
            val completedAt = attempt.view.completedAt
            attempt.view.startedAt <= createdAt && (completedAt == null || completedAt >= createdAt)
        }
        ?.view?.id

    private fun technicalName(job: StoredV2Job) =
        "${job.view.tenantId}-${job.view.jobKind.name.lowercase().replace('_', '-')}-${job.view.id.take(8)}"

    private fun preview(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(AdminController.PREVIEW_CHARACTERS)
    private fun encodeCursor(offset: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(offset.toString().toByteArray(StandardCharsets.UTF_8))
    private fun decodeCursor(cursor: String?): Int = runCatching {
        String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).toInt()
    }.getOrDefault(0)

    private fun aggregateCosts(costs: List<CostValue>): List<CostValue> = costs
        .groupBy { Triple(it.kind, it.status, it.currency) }
        .map { (key, values) ->
            CostValue(
                key.first, key.second,
                values.fold(BigDecimal.ZERO) { total, cost -> total + BigDecimal(cost.amount) }.stripTrailingZeros().toPlainString(),
                key.third,
            )
        }
        .sortedWith(compareBy<CostValue> { it.currency }.thenBy { it.kind.name })

    companion object {
        private val TERMINAL_STATUSES = setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED)
    }
}
