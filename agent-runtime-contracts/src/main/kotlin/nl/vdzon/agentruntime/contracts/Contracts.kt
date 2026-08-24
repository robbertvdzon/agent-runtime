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

data class ResourceRequest(
    @field:NotBlank @field:Size(max = 100) val key: String,
)

data class CreateJobRequest(
    @field:NotNull val jobKind: JobKind,
    @field:NotBlank @field:Size(max = 160) val idempotencyKey: String,
    @field:NotBlank @field:Size(max = 100) val jobProfile: String,
    @field:NotBlank @field:Size(max = 160) val jobKey: String,
    @field:NotNull val provider: Provider,
    @field:NotBlank @field:Size(max = 100) val model: String,
    @field:NotBlank @field:Size(max = 80) val configurationVersion: String,
    @field:NotBlank @field:Size(max = 80) val instructionVersion: String,
    @field:NotBlank @field:Size(max = 200_000) val instructions: String,
    @field:NotNull val input: JsonNode,
    val responseSchema: JsonNode? = null,
    @field:Valid val repositorySnapshot: RepositorySnapshot? = null,
    @field:Valid val repositoryRequest: RepositoryRequest? = null,
    @field:Valid @field:Size(max = 20) val resourceRequests: List<ResourceRequest> = emptyList(),
    @field:Min(30) @field:Max(86_400) val executionTimeoutSeconds: Int = 3_600,
    @field:Min(1) @field:Max(10) val maxAttempts: Int = 3,
    @field:Min(0) @field:Max(100) val priority: Int = 50,
    val consumerContext: JsonNode? = null,
)

data class JobView(
    val id: String,
    val tenantId: String,
    val jobKind: JobKind,
    val idempotencyKey: String,
    val jobProfile: String,
    val jobKey: String,
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
    val versions: Map<String, String> = emptyMap(),
)

data class WorkerView(
    val workerId: String,
    val bootId: String,
    val status: String,
    val capabilities: Set<String>,
    val providers: Set<Provider>,
    val models: Set<String>,
    val lastHeartbeatAt: Instant,
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

data class FailAttemptRequest(
    @field:NotBlank val attemptId: String,
    @field:NotBlank val fencingToken: String,
    @field:NotBlank @field:Size(max = 120) val errorCode: String,
    @field:NotBlank @field:Size(max = 2_000) val message: String,
    val retryable: Boolean,
)

data class PrepareMockResponseRequest(
    @field:Size(max = 100) val tenantId: String? = null,
    @field:Size(max = 100) val jobProfile: String? = null,
    @field:Size(max = 160) val jobKey: String? = null,
    val consumerCorrelation: String? = null,
    val result: JsonNode? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    @field:Min(0) @field:Max(60_000) val delayMillis: Long = 0,
)

data class ErrorResponse(val code: String, val message: String, val timestamp: Instant = Instant.now())
