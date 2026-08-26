package nl.vdzon.agentruntime.contracts

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

enum class JobKind { APPLICATION_WORK, REPOSITORY_WORK }
enum class Provider { CODEX, CLAUDE, MOCKED }
enum class JobStatus { QUEUED, WAITING_FOR_WORKER, RUNNING, SUCCEEDED, FAILED, CANCELLED }

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

data class InputAttachmentRequest(
    @field:NotBlank @field:Size(max = 255) val filename: String,
    @field:NotBlank @field:Size(max = 160) val mimeType: String,
    @field:NotBlank @field:Size(max = 2_800_000) val contentBase64: String,
)

data class CreateJobRequest(
    @field:NotNull val jobKind: JobKind,
    @field:NotBlank @field:Size(max = 160) val idempotencyKey: String,
    @field:NotNull val provider: Provider,
    @field:NotBlank @field:Size(max = 100) val model: String,
    @field:NotBlank @field:Size(max = 200_000) val prompt: String,
    val responseSchema: JsonNode? = null,
    @field:Valid val repositorySnapshot: RepositorySnapshot? = null,
    @field:Valid val repositoryRequest: RepositoryRequest? = null,
    @field:Size(max = 50) val environmentKeys: List<String> = emptyList(),
    @field:Valid @field:Size(max = 10) val attachments: List<InputAttachmentRequest> = emptyList(),
    @field:Min(30) @field:Max(86_400) val executionTimeoutSeconds: Int = 3_600,
)

data class JobView(
    val id: String,
    val tenantId: String,
    val jobKind: JobKind,
    val idempotencyKey: String,
    val provider: Provider,
    val model: String,
    val status: JobStatus,
    val phase: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val priority: Int,
    val progressPercent: Int?,
    val progressMessage: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val notBefore: Instant?,
)

data class JobResultView(
    val jobId: String,
    val result: JsonNode,
    val usage: JsonNode? = null,
    val artifacts: List<ArtifactView> = emptyList(),
    val completedAt: Instant,
)

data class JobEventView(
    val id: Long,
    val jobId: String,
    val type: String,
    val phase: String,
    val message: String?,
    val createdAt: Instant,
)

data class ArtifactView(
    val id: String,
    val jobId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Instant,
)

data class WorkerRegistrationRequest(
    @field:NotBlank @field:Size(max = 100) val workerId: String,
    @field:NotBlank @field:Size(max = 100) val bootId: String,
    @field:Size(max = 20) val capabilities: Set<String>,
    @field:Size(max = 10) val providers: Set<Provider>,
    @field:Size(max = 30) val models: Set<String>,
    @field:Size(max = 1_000) val availableEnvironmentKeys: Set<String> = emptySet(),
    @field:Min(1) @field:Max(32) val maxConcurrency: Int = 1,
    val versions: Map<String, String> = emptyMap(),
)

data class WorkerView(
    val workerId: String,
    val bootId: String,
    val status: String,
    val capabilities: Set<String>,
    val providers: Set<Provider>,
    val models: Set<String>,
    val availableEnvironmentKeys: Set<String>,
    val maxConcurrency: Int,
    val lastHeartbeatAt: Instant,
)

data class EnvironmentKeyView(
    val name: String,
    val projectPrefix: String,
    val available: Boolean,
    val matchingOnlineWorkers: Int,
    val lastSeenAt: Instant,
)

data class ClaimRequest(
    @field:NotBlank val bootId: String,
    @field:Size(max = 20) val capabilities: Set<String>,
    @field:Size(max = 10) val providers: Set<Provider>,
    @field:Size(max = 30) val models: Set<String>,
    @field:Min(0) @field:Max(25) val waitSeconds: Int = 20,
)

data class ClaimedJob(
    val job: JobView,
    val attemptId: String,
    val fencingToken: String,
    val leaseUntil: Instant,
    val attemptDeadline: Instant,
    val request: CreateJobRequest,
)

data class AttemptAuth(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
)

data class HeartbeatRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank val bootId: String,
)

data class HeartbeatResponse(
    val accepted: Boolean,
    val cancelRequested: Boolean,
    val fenced: Boolean,
    val leaseUntil: Instant?,
)

data class ProgressRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank @field:Size(max = 80) val phase: String,
    @field:Min(0) @field:Max(100) val percent: Int? = null,
    @field:Size(max = 1_000) val message: String? = null,
)

data class CompleteAttemptRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotNull val result: JsonNode,
    val usage: JsonNode? = null,
)

data class StartOutputAttemptRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank @field:Size(max = 160) val idempotencyKey: String,
)

data class JsonValidationError(
    val path: String,
    val keyword: String,
    val message: String,
)

data class OutputAttemptView(
    val outputAttemptId: String,
    val outputAttemptNumber: Int,
    val maxOutputAttempts: Int,
    val correctionErrors: List<JsonValidationError> = emptyList(),
)

data class SubmitOutputCandidateRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank val outputAttemptId: String,
    @field:Size(min = 1, max = 5 * 1024 * 1024) val candidateText: String,
    val usage: JsonNode? = null,
)

enum class OutputCandidateStatus { ACCEPTED, CORRECTION_REQUIRED, EXHAUSTED }

data class SubmitOutputCandidateResponse(
    val status: OutputCandidateStatus,
    val errorCode: String? = null,
    val validationErrors: List<JsonValidationError> = emptyList(),
    val outputAttemptsRemaining: Int,
)

data class FinalizeAcceptedOutputRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank val outputAttemptId: String,
)

enum class TranscriptKind { PROMPT, AGENT_TEXT, TOOL_CALL, TOOL_OUTPUT, CORRECTION, PROVIDER_RESULT }

data class AppendTranscriptRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank @field:Size(max = 100) val partId: String,
    @field:Min(1) val sequence: Long,
    @field:NotNull val kind: TranscriptKind,
    @field:NotBlank @field:Size(max = 100_000) val text: String,
)

data class TranscriptPartView(
    val jobId: String,
    val attemptId: String,
    val partId: String,
    val sequence: Long,
    val createdAt: Instant,
    val kind: TranscriptKind,
    val text: String,
    val redacted: Boolean,
)

data class TranscriptPage(
    val items: List<TranscriptPartView>,
    val nextSequence: Long?,
    val active: Boolean,
)

data class FailAttemptRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank @field:Size(max = 120) val errorCode: String,
    @field:NotBlank @field:Size(max = 2_000) val message: String,
    val retryable: Boolean,
)

data class PrepareMockResponseRequest(
    @field:Size(max = 100) val tenantId: String? = null,
    @field:Size(max = 160) val idempotencyKey: String? = null,
    val result: JsonNode? = null,
    @field:Size(max = 3) val outputSequence: List<String> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    @field:Min(0) @field:Max(60_000) val delayMillis: Long = 0,
)

data class ErrorResponse(val code: String, val message: String, val timestamp: Instant = Instant.now())
