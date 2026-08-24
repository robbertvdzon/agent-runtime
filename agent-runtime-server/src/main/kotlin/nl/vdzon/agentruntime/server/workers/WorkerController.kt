package nl.vdzon.agentruntime.server.workers

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import nl.vdzon.agentruntime.server.jobs.JobStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.util.HexFormat

@RestController
@RequestMapping("/v1/workers")
class WorkerController(
    private val execution: ExecutionService,
    private val workers: WorkerStore,
    private val jobs: JobStore,
    private val properties: RuntimeProperties,
) {
    @PostMapping("/register")
    fun register(@Valid @RequestBody body: WorkerRegistrationRequest, request: HttpServletRequest): WorkerView {
        requireWorker(request)
        return execution.register(body)
    }

    @PostMapping("/{workerId}/claims")
    fun claim(@PathVariable workerId: String, @Valid @RequestBody body: ClaimRequest, request: HttpServletRequest): ResponseEntity<ClaimedJob> {
        requireWorker(request)
        val claimed = execution.claim(workerId, body)
        return if (claimed == null) ResponseEntity.status(HttpStatus.NO_CONTENT).build() else ResponseEntity.ok(claimed)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/heartbeat")
    fun heartbeat(@PathVariable workerId: String, @PathVariable jobId: String, @Valid @RequestBody body: HeartbeatRequest, request: HttpServletRequest): HeartbeatResponse {
        requireWorker(request)
        return execution.heartbeat(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/progress")
    fun progress(@PathVariable workerId: String, @PathVariable jobId: String, @Valid @RequestBody body: ProgressRequest, request: HttpServletRequest) {
        requireWorker(request); execution.progress(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/complete")
    fun complete(@PathVariable workerId: String, @PathVariable jobId: String, @Valid @RequestBody body: CompleteAttemptRequest, request: HttpServletRequest) {
        requireWorker(request); execution.complete(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/fail")
    fun fail(@PathVariable workerId: String, @PathVariable jobId: String, @Valid @RequestBody body: FailAttemptRequest, request: HttpServletRequest) {
        requireWorker(request); execution.fail(jobId, workerId, body)
    }

    @PutMapping("/{workerId}/jobs/{jobId}/artifacts", consumes = ["application/octet-stream"])
    fun upload(
        @PathVariable workerId: String, @PathVariable jobId: String,
        @RequestHeader("X-Attempt-Id") attemptId: String, @RequestHeader("X-Fencing-Token") token: String,
        @RequestHeader("X-Filename") filename: String, @RequestHeader("X-Content-SHA256") expectedSha: String,
        @RequestHeader("X-Mime-Type", defaultValue = "application/octet-stream") mimeType: String,
        @RequestBody content: ByteArray, request: HttpServletRequest,
    ): ArtifactView {
        requireWorker(request)
        execution.authenticateArtifact(jobId, workerId, attemptId, token)
        if (content.size > properties.artifactMaxBytes) throw ApiException("ARTIFACT_TOO_LARGE", "Artifact exceeds the profile limit.", HttpStatus.PAYLOAD_TOO_LARGE)
        if (jobs.totalArtifactBytes(jobId) + content.size > properties.jobArtifactMaxBytes) throw ApiException("JOB_ARTIFACT_LIMIT", "Job artifact limit exceeded.", HttpStatus.PAYLOAD_TOO_LARGE)
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedSha.lowercase().toByteArray())) throw ApiException("ARTIFACT_HASH_MISMATCH", "Artifact SHA-256 does not match.")
        return jobs.insertArtifact(jobId, filename, mimeType, actual, content)
    }

    @GetMapping
    fun list(request: HttpServletRequest): List<WorkerView> {
        if (ApiSecurity.identity(request).role != PrincipalRole.ADMIN) throw ApiException("FORBIDDEN", "Administrator required.", HttpStatus.FORBIDDEN)
        return workers.listWorkers()
    }

    private fun requireWorker(request: HttpServletRequest) {
        if (ApiSecurity.identity(request).role != PrincipalRole.WORKER) throw ApiException("FORBIDDEN", "Worker identity required.", HttpStatus.FORBIDDEN)
    }
}
