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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun progress(@PathVariable workerId: String, @PathVariable jobId: String, @Valid @RequestBody body: ProgressRequest, request: HttpServletRequest) {
        requireWorker(request); execution.progress(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/transcript")
    fun transcript(
        @PathVariable workerId: String, @PathVariable jobId: String,
        @Valid @RequestBody body: AppendTranscriptRequest, request: HttpServletRequest,
    ): TranscriptPartView {
        requireWorker(request)
        return execution.appendTranscript(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/output-attempts")
    fun startOutputAttempt(
        @PathVariable workerId: String, @PathVariable jobId: String,
        @Valid @RequestBody body: StartOutputAttemptRequest, request: HttpServletRequest,
    ): OutputAttemptView {
        requireWorker(request)
        return execution.startOutputAttempt(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/output-candidates")
    fun submitOutputCandidate(
        @PathVariable workerId: String, @PathVariable jobId: String,
        @Valid @RequestBody body: SubmitOutputCandidateRequest, request: HttpServletRequest,
    ): SubmitOutputCandidateResponse {
        requireWorker(request)
        return execution.submitOutputCandidate(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/output-finalization")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun finalizeAcceptedOutput(
        @PathVariable workerId: String, @PathVariable jobId: String,
        @Valid @RequestBody body: FinalizeAcceptedOutputRequest, request: HttpServletRequest,
    ) {
        requireWorker(request)
        execution.finalizeAcceptedOutput(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun complete(@PathVariable workerId: String, @PathVariable jobId: String, @Valid @RequestBody body: CompleteAttemptRequest, request: HttpServletRequest) {
        requireWorker(request); execution.complete(jobId, workerId, body)
    }

    @PostMapping("/{workerId}/jobs/{jobId}/fail")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
        if (!SAFE_FILENAME.matches(filename) || filename in setOf(".", "..")) throw ApiException("UNSAFE_ARTIFACT", "Artifact filename is unsafe.")
        if (mimeType !in ALLOWED_ARTIFACT_MIME_TYPES) throw ApiException("ARTIFACT_MIME_UNSUPPORTED", "Artifact MIME type is not allowed.")
        if (jobs.artifacts(jobId).size >= 75 && jobs.artifacts(jobId).none { it.filename == filename }) throw ApiException("JOB_ARTIFACT_LIMIT", "Job has the maximum number of artifacts.", HttpStatus.PAYLOAD_TOO_LARGE)
        if (content.size > properties.artifactMaxBytes) throw ApiException("ARTIFACT_TOO_LARGE", "Artifact exceeds the server limit.", HttpStatus.PAYLOAD_TOO_LARGE)
        if (jobs.totalArtifactBytes(jobId) + content.size > properties.jobArtifactMaxBytes) throw ApiException("JOB_ARTIFACT_LIMIT", "Job artifact limit exceeded.", HttpStatus.PAYLOAD_TOO_LARGE)
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedSha.lowercase().toByteArray())) throw ApiException("ARTIFACT_HASH_MISMATCH", "Artifact SHA-256 does not match.")
        if (!matchesMagic(mimeType, content)) throw ApiException("ARTIFACT_MIME_MISMATCH", "Artifact content does not match its MIME type.")
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

    private fun matchesMagic(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP"
        "application/pdf" -> bytes.size >= 5 && String(bytes, 0, 5) == "%PDF-"
        else -> true
    }

    companion object {
        private val SAFE_FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
        private val ALLOWED_ARTIFACT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain", "application/json", "text/csv", "application/octet-stream")
    }
}
