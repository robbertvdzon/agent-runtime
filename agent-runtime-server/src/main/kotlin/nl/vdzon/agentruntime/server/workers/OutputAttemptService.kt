package nl.vdzon.agentruntime.server.workers

import io.micrometer.core.instrument.MeterRegistry
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import nl.vdzon.agentruntime.server.jobs.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat

@Service
class OutputAttemptService(
    private val jobs: JobStore,
    private val attempts: WorkerStore,
    private val outputs: OutputAttemptStore,
    private val validator: JsonResultValidator,
    private val properties: RuntimeProperties,
    private val metrics: MeterRegistry,
) {
    @Transactional
    fun start(jobId: String, attempt: AttemptRecord, request: StartOutputAttemptRequest): OutputAttemptView {
        val job = jobs.find(jobId) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        if (job.view.jobKind != JobKind.APPLICATION_WORK) throw ApiException("OUTPUT_ATTEMPT_NOT_ALLOWED", "Output attempts are only used for application work.")
        outputs.findByIdempotency(jobId, request.idempotencyKey)?.let { return view(it, jobId) }
        if (outputs.count(jobId) >= properties.maxOutputAttempts) {
            attempts.finish(attempt.id, "FAILED")
            jobs.fail(jobId, "OUTPUT_ATTEMPTS_INTERRUPTED", "Output attempt budget was exhausted by interrupted executions.")
            throw ApiException("OUTPUT_ATTEMPTS_INTERRUPTED", "No output attempt budget remains.", HttpStatus.CONFLICT)
        }
        val output = outputs.start(job, attempt.id, request.idempotencyKey)
        jobs.addEvent(jobId, "OUTPUT_ATTEMPT_STARTED", "VALIDATING", "Output attempt ${output.number} started.")
        return view(output, jobId)
    }

    @Transactional
    fun submit(jobId: String, attempt: AttemptRecord, request: SubmitOutputCandidateRequest): SubmitOutputCandidateResponse {
        val job = jobs.find(jobId) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        val output = outputs.find(request.outputAttemptId)
            ?: throw ApiException("OUTPUT_ATTEMPT_NOT_FOUND", "Output attempt not found.", HttpStatus.NOT_FOUND)
        if (output.jobId != jobId || output.executionAttemptId != attempt.id || output.status != "RESERVED") {
            throw ApiException("OUTPUT_ATTEMPT_FENCED", "Output attempt is not current and writable.", HttpStatus.CONFLICT)
        }
        val validation = validator.validateCandidate(job.request.responseSchema, request.candidateText)
        val sha256 = sha256(request.candidateText)
        if (validation.result != null) {
            outputs.accept(output.id, sha256, validation.result, request.usage)
            jobs.addEvent(jobId, "OUTPUT_ACCEPTED", "UPLOADING_ARTIFACTS", "Output attempt ${output.number} accepted; artifacts may now be uploaded.")
            jobs.progress(jobId, "UPLOADING_ARTIFACTS", 90, "Validated output accepted; uploading controlled artifacts.")
            metrics.counter("agent_runtime_output_accepted", "attempt", output.number.toString(), "provider", job.view.provider.name, "model", job.view.model).increment()
            metrics.summary("agent_runtime_output_attempts_per_success", "provider", job.view.provider.name, "model", job.view.model).record(output.number.toDouble())
            return SubmitOutputCandidateResponse(OutputCandidateStatus.ACCEPTED, outputAttemptsRemaining = properties.maxOutputAttempts - output.number)
        }

        outputs.reject(output.id, sha256, Redactor.clean(validation.normalizedText, 2_000).orEmpty(), validation.errors)
        val event = if (validation.errorCode == "MODEL_OUTPUT_NOT_JSON") "OUTPUT_REJECTED_NOT_JSON" else "OUTPUT_REJECTED_SCHEMA"
        jobs.addEvent(jobId, event, "VALIDATING", "Output attempt ${output.number} rejected: ${validation.errorCode}.")
        metrics.counter("agent_runtime_output_rejected", "code", validation.errorCode ?: "UNKNOWN", "provider", job.view.provider.name, "model", job.view.model).increment()
        val terminal = validation.errorCode == "RESULT_TOO_LARGE" || output.number >= properties.maxOutputAttempts
        if (terminal) {
            jobs.addEvent(jobId, "OUTPUT_ATTEMPTS_EXHAUSTED", "FAILED", "No valid model output remained.")
            attempts.finish(attempt.id, "FAILED")
            val code = if (validation.errorCode == "RESULT_TOO_LARGE") "RESULT_TOO_LARGE" else "MODEL_OUTPUT_RETRIES_EXHAUSTED"
            jobs.fail(jobId, code, validation.errors.joinToString("; ") { "${it.path}: ${it.message}" })
            metrics.counter("agent_runtime_output_exhausted", "provider", job.view.provider.name, "model", job.view.model).increment()
            return SubmitOutputCandidateResponse(OutputCandidateStatus.EXHAUSTED, code, validation.errors, 0)
        }
        jobs.addEvent(jobId, "OUTPUT_CORRECTION_REQUESTED", "EXECUTING", "A corrected complete JSON result is required.")
        return SubmitOutputCandidateResponse(
            OutputCandidateStatus.CORRECTION_REQUIRED, validation.errorCode, validation.errors,
            properties.maxOutputAttempts - output.number,
        )
    }

    @Transactional
    fun finalize(jobId: String, attempt: AttemptRecord, request: FinalizeAcceptedOutputRequest) {
        val output = outputs.find(request.outputAttemptId)
            ?: throw ApiException("OUTPUT_ATTEMPT_NOT_FOUND", "Output attempt not found.", HttpStatus.NOT_FOUND)
        if (output.jobId != jobId || output.executionAttemptId != attempt.id || output.status != "ACCEPTED" || output.acceptedResult == null) {
            throw ApiException("OUTPUT_ATTEMPT_FENCED", "Accepted output does not belong to this active attempt.", HttpStatus.CONFLICT)
        }
        attempts.finish(attempt.id, "SUCCEEDED")
        jobs.complete(jobId, output.acceptedResult, output.usage)
    }

    @Transactional
    fun executeMock(job: StoredJob, candidates: List<String>): Boolean {
        candidates.take(properties.maxOutputAttempts).forEachIndexed { index, candidate ->
            val output = outputs.start(job, null, "mock-${job.view.id}-${index + 1}")
            jobs.addEvent(job.view.id, "OUTPUT_ATTEMPT_STARTED", "VALIDATING", "Mock output attempt ${output.number} started.")
            val validation = validator.validateCandidate(job.request.responseSchema, candidate)
            if (validation.result != null) {
                outputs.accept(output.id, sha256(candidate), validation.result, null)
                jobs.addEvent(job.view.id, "OUTPUT_ACCEPTED", "COMPLETED", "Mock output accepted.")
                jobs.completeMock(job.view.id, validation.result)
                metrics.counter("agent_runtime_output_accepted", "attempt", output.number.toString(), "provider", job.view.provider.name, "model", job.view.model).increment()
                return true
            }
            outputs.reject(output.id, sha256(candidate), Redactor.clean(validation.normalizedText, 2_000).orEmpty(), validation.errors)
            metrics.counter("agent_runtime_output_rejected", "code", validation.errorCode ?: "UNKNOWN", "provider", job.view.provider.name, "model", job.view.model).increment()
            jobs.addEvent(job.view.id, if (validation.errorCode == "MODEL_OUTPUT_NOT_JSON") "OUTPUT_REJECTED_NOT_JSON" else "OUTPUT_REJECTED_SCHEMA", "VALIDATING", validation.errorCode)
            if (validation.errorCode == "RESULT_TOO_LARGE") {
                jobs.fail(job.view.id, "RESULT_TOO_LARGE", "Mock output exceeds the result limit.")
                return true
            }
        }
        jobs.addEvent(job.view.id, "OUTPUT_ATTEMPTS_EXHAUSTED", "FAILED", "Mock output attempts exhausted.")
        jobs.fail(job.view.id, "MODEL_OUTPUT_RETRIES_EXHAUSTED", "No prepared mock candidate satisfied the response contract.")
        metrics.counter("agent_runtime_output_exhausted", "provider", job.view.provider.name, "model", job.view.model).increment()
        return true
    }

    fun list(jobId: String): List<OutputAttemptRecord> = outputs.list(jobId)
    fun abandon(executionAttemptId: String) = outputs.abandonReservedForExecution(executionAttemptId)

    private fun view(output: OutputAttemptRecord, jobId: String): OutputAttemptView {
        val previousErrors = outputs.list(jobId).lastOrNull { it.number < output.number && it.status == "REJECTED" }?.validationErrors ?: emptyList()
        return OutputAttemptView(output.id, output.number, properties.maxOutputAttempts, previousErrors)
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
}
