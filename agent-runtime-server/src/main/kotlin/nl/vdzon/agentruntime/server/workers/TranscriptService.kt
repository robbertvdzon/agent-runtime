package nl.vdzon.agentruntime.server.workers

import io.micrometer.core.instrument.MeterRegistry
import nl.vdzon.agentruntime.contracts.AppendTranscriptRequest
import nl.vdzon.agentruntime.contracts.TranscriptPartView
import nl.vdzon.agentruntime.server.jobs.JobStore
import nl.vdzon.agentruntime.server.jobs.TranscriptStore
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class TranscriptService(
    private val transcripts: TranscriptStore,
    private val jobs: JobStore,
    private val metrics: MeterRegistry,
    private val properties: RuntimeProperties,
) {
    fun append(jobId: String, attemptId: String, request: AppendTranscriptRequest): TranscriptPartView {
        val clean = Redactor.clean(request.text, 100_000).orEmpty()
        if (transcripts.find(attemptId, request.partId) == null && transcripts.totalBytes(jobId) + clean.toByteArray().size > properties.transcriptMaxBytesPerJob) {
            metrics.counter("agent_runtime_transcript_ingest_errors", "code", "TRANSCRIPT_LIMIT_EXCEEDED").increment()
            throw ApiException("TRANSCRIPT_LIMIT_EXCEEDED", "Transcript storage limit reached after sequence ${request.sequence - 1}.", HttpStatus.PAYLOAD_TOO_LARGE)
        }
        val redacted = clean != request.text || clean.contains("[REDACTED]")
        val part = transcripts.append(jobId, attemptId, request.partId, request.sequence, request.kind, clean, redacted)
        metrics.counter("agent_runtime_transcript_ingest", "kind", request.kind.name).increment()
        if (redacted) metrics.counter("agent_runtime_transcript_redactions").increment()
        jobs.addEvent(jobId, "TRANSCRIPT_APPENDED", "EXECUTING", "Transcript ${request.sequence} (${request.kind}) stored${if (redacted) " with redaction" else ""}.")
        return part
    }
}
