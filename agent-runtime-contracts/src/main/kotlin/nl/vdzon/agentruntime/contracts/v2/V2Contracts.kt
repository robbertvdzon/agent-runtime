package nl.vdzon.agentruntime.contracts.v2

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

enum class JobKind { APPLICATION_WORK, REPOSITORY_WORK }
enum class TaskType { STRUCTURED_GENERATION, REPOSITORY_AGENT, TRANSCRIPTION, SPEECH_SYNTHESIS, WEB_SEARCH, EMBEDDING, IMAGE_GENERATION }
enum class ExecutionMode { SUBSCRIPTION, API, MOCK }
enum class JobStatus { QUEUED, WAITING_FOR_WORKER, RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class AttemptReason { INITIAL, INVALID_OUTPUT, PROVIDER_ERROR, TECHNICAL_ERROR }
enum class AttemptStatus { RUNNING, SUCCEEDED, FAILED, INVALID_OUTPUT, ABANDONED, CANCELLED }
enum class UsageQuality { COMPLETE, PARTIAL, UNAVAILABLE, MOCK }
enum class UsageMetric { INPUT_TOKENS, CACHED_INPUT_TOKENS, CACHE_WRITE_TOKENS, OUTPUT_TOKENS, REASONING_TOKENS, AUDIO_INPUT_SECONDS, AUDIO_OUTPUT_SECONDS, CHARACTERS, SEARCH_REQUESTS, EXTRACT_REQUESTS, IMAGES }
enum class UsageUnit { TOKEN, SECOND, CHARACTER, REQUEST, IMAGE }
enum class UsageSource { PROVIDER_REPORTED, WORKER_MEASURED, MOCK }
enum class InputRole { SOURCE, CONTEXT, PROMPT, IMAGE, AUDIO, VIDEO, DOCUMENT }
enum class EventType { JOB_STATUS_CHANGED, ATTEMPT_STARTED, ATTEMPT_FINISHED, RETRY_SCHEDULED, PROGRESS_UPDATED, LOG_MESSAGE, OUTPUT_OBJECT_READY, JOB_FINISHED }
enum class LogKind { AGENT_TEXT, REASONING_SUMMARY, TOOL_CALL, TOOL_OUTPUT, SYSTEM }
enum class CostKind { DIRECT, CALCULATED, ALLOCATED }
enum class CostStatus { ESTIMATED, FINAL, RECONCILED }
enum class AllocationMethod { NONE, WEIGHTED_TOKENS, REPORTED_QUOTA_UNITS }
enum class SubscriptionStatus { OPEN, ALLOCATED, FINAL }

data class ExecutionSelection(
    @field:Pattern(regexp = "[a-z][a-z0-9-]{0,99}") val vendorId: String,
    @field:NotBlank @field:Size(max = 160) val model: String,
    val mode: ExecutionMode,
)

data class InputObjectRef(
    @field:Pattern(regexp = "[0-9a-fA-F-]{36}") val objectId: String,
    @field:Pattern(regexp = "[a-z][a-z0-9-]{0,99}") val name: String,
    val role: InputRole,
)

data class JobInput(
    @field:NotBlank @field:Size(max = 65_536) val instruction: String,
    @field:Valid @field:Size(max = 50) val objects: List<InputObjectRef> = emptyList(),
)

data class OutputArtifactDeclaration(
    @field:Pattern(regexp = "[a-z][a-z0-9-]{0,99}") val name: String,
    val required: Boolean,
    @field:NotEmpty @field:Size(max = 20) val mimeTypes: Set<@NotBlank String>,
    @field:Min(1) @field:Max(2_147_483_648L) val maxBytes: Long? = null,
)

data class OutputContract(
    val resultSchema: JsonNode? = null,
    @field:Valid @field:Size(max = 50) val artifacts: List<OutputArtifactDeclaration> = emptyList(),
)

data class RepositorySnapshot(
    @field:Pattern(regexp = "https://.+") val url: String,
    @field:Pattern(regexp = "[0-9a-fA-F]{40}") val commitSha: String,
)

data class RepositoryRequest(
    @field:NotBlank @field:Size(max = 100) val alias: String,
    @field:NotBlank @field:Size(max = 120) val baseBranch: String,
    @field:Size(max = 120) val branchHint: String? = null,
    val publish: Boolean = true,
)

data class CreateJobRequest(
    @field:NotBlank @field:Size(max = 160) val idempotencyKey: String,
    val jobKind: JobKind,
    val taskType: TaskType,
    @field:Valid val execution: ExecutionSelection,
    @field:Valid val input: JobInput,
    @field:Valid val output: OutputContract,
    @field:Valid val repositorySnapshot: RepositorySnapshot? = null,
    @field:Valid val repositoryRequest: RepositoryRequest? = null,
    @field:Size(max = 50) val environmentKeys: List<@Pattern(regexp = "[A-Z][A-Z0-9_]*__[A-Z][A-Z0-9_]*") String> = emptyList(),
    @field:Min(30) @field:Max(86_400) val executionTimeoutSeconds: Int = 3_600,
)

data class CreateUploadRequest(
    @field:Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,254}") val filename: String,
    @field:NotBlank @field:Size(max = 160) val mimeType: String,
    @field:Min(1) @field:Max(2_147_483_648L) val sizeBytes: Long,
    @field:Pattern(regexp = "[0-9a-f]{64}") val sha256: String,
)

data class UploadView(
    val uploadId: String,
    val objectId: String,
    val state: String,
    val protocol: String = "RESUMABLE_PATCH",
    val chunkSizeBytes: Long,
    val uploadUrl: String,
    val offset: Long,
    val sizeBytes: Long,
    val expiresAt: Instant,
)

data class ObjectView(
    val objectId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: String,
    val createdAt: Instant,
    val readyAt: Instant?,
)

data class JobView(
    val id: String,
    val tenantId: String,
    val idempotencyKey: String,
    val jobKind: JobKind,
    val taskType: TaskType,
    val execution: ExecutionSelection,
    val status: JobStatus,
    val phase: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val progressPercent: Int?,
    val progressMessage: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

data class JobPage(val items: List<JobView>, val nextCursor: String? = null)

data class UsageMetricValue(val metric: UsageMetric, val quantity: String, val unit: UsageUnit)
data class CostValue(val kind: CostKind, val status: CostStatus, val amount: String, val currency: String)
data class UsageShareValue(val metric: UsageMetric, val percentage: String)
data class JobUsageSummary(
    val attemptCount: Int,
    val usageQuality: UsageQuality,
    val metrics: List<UsageMetricValue> = emptyList(),
    val costs: List<CostValue> = emptyList(),
)

data class OutputObjectView(
    val objectId: String,
    val name: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: String,
    val createdAt: Instant,
    val readyAt: Instant,
    val downloadUrl: String,
)

data class JobResultView(
    val jobId: String,
    val result: JsonNode,
    val artifacts: List<OutputObjectView>,
    val usageSummary: JobUsageSummary,
    val completedAt: Instant,
)

data class AttemptView(
    val id: String,
    val jobId: String,
    val number: Int,
    val reason: AttemptReason,
    val status: AttemptStatus,
    val execution: ExecutionSelection,
    val providerRequestId: String?,
    val usageQuality: UsageQuality,
    val usageSummary: JobUsageSummary,
    val errorCode: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
)

data class JobEventView(
    val sequence: Long,
    val jobId: String,
    val type: EventType,
    val phase: String?,
    val message: String?,
    val logKind: LogKind?,
    val logText: String?,
    val logStreamId: String?,
    val logFinal: Boolean?,
    val status: JobStatus?,
    val progressPercent: Int?,
    val createdAt: Instant,
)

data class JobEventPage(val items: List<JobEventView>, val nextSequence: Long?, val active: Boolean)
data class ContentDeletionView(val jobId: String, val status: String, val requestedAt: Instant, val completedAt: Instant?)

data class UsageSummaryRow(
    val dimensions: Map<String, String>,
    val jobCount: Long,
    val attemptCount: Long,
    val unknownUsageAttemptCount: Long,
    val metrics: List<UsageMetricValue>,
    val costs: List<CostValue>,
    val usageShares: List<UsageShareValue> = emptyList(),
)
data class UsageSummaryResponse(val from: Instant, val until: Instant, val rows: List<UsageSummaryRow>)

data class CreatePriceRateRequest(
    val vendorId: String, val model: String, val mode: ExecutionMode, val taskType: TaskType,
    val metric: UsageMetric, val unitSize: String, val unitPrice: String, val currency: String,
    val validFrom: Instant, val validUntil: Instant? = null, val sourceReference: String,
)
data class PriceRateView(
    val id: String, val version: Int, val vendorId: String, val model: String, val mode: ExecutionMode,
    val taskType: TaskType, val metric: UsageMetric, val unitSize: String, val unitPrice: String,
    val currency: String, val validFrom: Instant, val validUntil: Instant?, val sourceReference: String,
    val createdAt: Instant,
)
data class CreateSubscriptionPeriodRequest(
    val vendorId: String, val model: String, val periodStart: LocalDate, val periodEnd: LocalDate,
    val amount: String, val currency: String, val allocationMethod: AllocationMethod,
)
data class SubscriptionPeriodView(
    val id: String, val vendorId: String, val model: String, val periodStart: LocalDate,
    val periodEnd: LocalDate, val amount: String, val currency: String,
    val allocationMethod: AllocationMethod, val status: SubscriptionStatus, val createdAt: Instant,
)

data class ExecutorCapability(
    val vendorId: String,
    val model: String,
    val mode: ExecutionMode,
    @field:NotEmpty val taskTypes: Set<TaskType>,
)
data class WorkerRegistrationRequest(
    @field:NotBlank val workerId: String,
    @field:NotBlank val bootId: String,
    @field:Valid @field:NotEmpty @field:Size(max = 100) val executors: Set<ExecutorCapability>,
    @field:Size(max = 1_000) val availableEnvironmentKeys: Set<String> = emptySet(),
    @field:Min(1) @field:Max(32) val maxConcurrency: Int = 1,
    val versions: Map<String, String> = emptyMap(),
)
data class WorkerView(val workerId: String, val bootId: String, val executors: Set<ExecutorCapability>, val maxConcurrency: Int, val lastHeartbeatAt: Instant)
data class ExecutionOptionView(
    val execution: ExecutionSelection,
    val taskTypes: Set<TaskType>,
    val available: Boolean,
    val matchingOnlineWorkers: Int,
    val lastSeenAt: Instant,
)
data class EnvironmentKeyOptionView(
    val name: String,
    val projectPrefix: String,
    val available: Boolean,
    val matchingOnlineWorkers: Int,
    val lastSeenAt: Instant,
)

data class CreateMockFixtureRequest(
    @field:NotBlank @field:Size(max = 100) val tenantId: String,
    @field:NotBlank @field:Size(max = 160) val idempotencyKey: String,
    val result: JsonNode? = null,
    @field:Size(max = 10) val outputSequence: List<@NotBlank @Size(max = 1_048_576) String> = emptyList(),
    @field:Size(max = 120) val errorCode: String? = null,
    @field:Size(max = 2_000) val errorMessage: String? = null,
    @field:Min(0) @field:Max(60_000) val delayMillis: Long = 0,
    @field:Size(max = 50) val outputArtifactNames: Set<@Pattern(regexp = "[a-z][a-z0-9-]{0,99}") String> = emptySet(),
)
data class MockFixtureView(
    val id: String,
    val tenantId: String,
    val idempotencyKey: String,
    val result: JsonNode?,
    val outputSequence: List<String>,
    val errorCode: String?,
    val errorMessage: String?,
    val delayMillis: Long,
    val outputArtifactNames: Set<String>,
    val createdAt: Instant,
)
data class ClaimRequest(
    @field:NotBlank val bootId: String,
    @field:Valid @field:NotEmpty @field:Size(max = 100) val executors: Set<ExecutorCapability>,
    @field:Min(0) @field:Max(25) val waitSeconds: Int = 20,
)
data class ClaimedJob(
    val job: JobView,
    val attempt: AttemptView,
    val fencingToken: String,
    val leaseUntil: Instant,
    val attemptDeadline: Instant,
    val request: CreateJobRequest,
)
data class AttemptAuth(val attemptId: String, @field:NotBlank val fencingToken: String)
data class HeartbeatResponse(val accepted: Boolean, val cancelRequested: Boolean, val fenced: Boolean, val leaseUntil: Instant?)
data class ProgressRequest(val attemptId: String, val fencingToken: String, @field:NotBlank val phase: String, val percent: Int? = null, val message: String? = null)
data class CreateOutputUploadRequest(
    val attemptId: String, val fencingToken: String, val name: String, val filename: String,
    val mimeType: String, val sizeBytes: Long, val sha256: String,
)
data class AppendUsageRequest(
    val fencingToken: String, val eventId: String, val observedAt: Instant,
    @field:NotEmpty val metrics: List<UsageMetricValue>, val providerRequestId: String? = null,
    val source: UsageSource,
)
data class AppendLogRequest(
    val fencingToken: String, val eventId: String, val kind: LogKind,
    @field:NotBlank @field:Size(max = 8_192) val text: String,
    val streamId: String? = null, val final: Boolean = true, val observedAt: Instant,
)
data class SubmitResultRequest(val fencingToken: String, val result: JsonNode, @field:Size(max = 50) val outputObjectIds: Set<String> = emptySet())
data class ValidationError(val path: String, val code: String, val message: String)
data class OutputRejectedResponse(val code: String, val retryScheduled: Boolean, val validationErrors: List<ValidationError>)
data class FailAttemptRequest(val fencingToken: String, val errorCode: String, val message: String, val retryable: Boolean)
