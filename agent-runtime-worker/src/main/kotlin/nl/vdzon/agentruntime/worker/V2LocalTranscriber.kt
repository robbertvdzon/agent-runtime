package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import nl.vdzon.agentruntime.contracts.v2.ClaimedJob
import nl.vdzon.agentruntime.contracts.v2.LogKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** LOCAL transcription: whisper.cpp runs directly on the macOS host (Metal), never inside Docker. */
class V2LocalTranscriber(private val config: WorkerConfig, private val client: V2RuntimeClient) {
    fun execute(claim: ClaimedJob) {
        val root = config.workRoot.resolve("v2-${claim.job.id}-${claim.attempt.id}")
        try {
            if (root.exists()) deleteTree(root)
            root.createDirectories()
            val model = config.whisperModels[claim.job.execution.model]?.takeIf(Path::isRegularFile)
                ?: throw JobFailure("PROVIDER_UNAVAILABLE", "Whisper model ${claim.job.execution.model} is unavailable on this worker.", true)
            val source = claim.request.input.objects.singleOrNull()
                ?: throw JobFailure("INVALID_TRANSCRIPTION_INPUT", "Transcription requires exactly one input object.", false)
            client.progress(claim, "PREPARING", 5, "Downloading audio input.")
            val input = root.resolve("input-audio")
            client.download(claim, source.objectId, input)
            val wav = root.resolve("audio.wav")
            client.progress(claim, "PREPARING", 15, "Converting audio to 16 kHz mono WAV.")
            run(claim, listOf(config.ffmpegBinary.toString(), "-nostdin", "-y", "-loglevel", "error", "-i", input.toString(), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav.toString()), root, "AUDIO_CONVERSION_FAILED")
            input.deleteIfExists()
            val seconds = ((wav.fileSize() - 44).coerceAtLeast(0) / 32_000L).coerceAtLeast(1)
            client.progress(claim, "CALLING_PROVIDER", 25, "Transcribing ${seconds / 60} minutes of audio with whisper.cpp.")
            val language = claim.request.transcription?.language ?: "auto"
            run(claim, listOf(config.whisperBinary.toString(), "-m", model.toString(), "-f", wav.toString(), "-l", language, "-t", config.whisperThreads.toString(), "-otxt", "-of", root.resolve("transcript").toString(), "-np"), root, "ENGINE_FAILED")
            val text = root.resolve("transcript.txt").takeIf(Path::isRegularFile)?.readLines()?.map(String::trim)?.filter(String::isNotBlank)?.joinToString("\n").orEmpty()
            if (text.isBlank()) throw JobFailure("PROVIDER_OUTPUT_MISSING", "whisper.cpp produced no transcript.", true)
            client.audioUsage(claim, seconds)
            client.progress(claim, "UPLOADING_OUTPUT", 90, "Uploading transcript.")
            val outputIds = claim.request.output.artifacts.map { declaration ->
                val mime = when {
                    "text/plain" in declaration.mimeTypes -> "text/plain"
                    "application/json" in declaration.mimeTypes -> "application/json"
                    else -> throw JobFailure("OUTPUT_MIME_NOT_SUPPORTED", "Transcription can produce text/plain or application/json.", false)
                }
                val file = root.resolve("artifact-${declaration.name}")
                file.writeText(if (mime == "application/json") JsonNodeFactory.instance.objectNode().put("text", text).toString() else text)
                declaration.maxBytes?.let { if (file.fileSize() > it) throw JobFailure("OUTPUT_TOO_LARGE", "Transcript exceeds declared maximum.", false) }
                client.upload(claim, declaration.name, file, mime)
            }.toSet()
            client.log(claim, LogKind.SYSTEM, "Local transcription finished: ${text.length} characters.")
            client.submit(claim, JsonNodeFactory.instance.objectNode().put("text", text), outputIds, null, null)
        } catch (failure: JobFailure) {
            runCatching { client.fail(claim, failure.code, failure.message.orEmpty(), failure.retryable) }
        } catch (error: Exception) {
            runCatching { client.fail(claim, "WORKER_ERROR", safe(error.message), true) }
        } finally {
            runCatching { deleteTree(root) }
        }
    }

    private fun run(claim: ClaimedJob, command: List<String>, directory: Path, failureCode: String) {
        val log = directory.resolve("process-${Instant.now().toEpochMilli()}.log")
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            val heartbeat = client.heartbeat(claim)
            if (!heartbeat.accepted || heartbeat.fenced || heartbeat.cancelRequested || !Instant.now().isBefore(claim.attemptDeadline)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                throw JobFailure(if (heartbeat.cancelRequested) "CANCELLED" else if (!heartbeat.accepted || heartbeat.fenced) "ATTEMPT_FENCED" else "EXECUTION_TIMEOUT", "Transcription was stopped.", false)
            }
        }
        if (process.exitValue() != 0) {
            val tail = runCatching { Files.readString(log).takeLast(1_000) }.getOrDefault("")
            throw JobFailure(failureCode, "${command.first().substringAfterLast('/')} exited with ${process.exitValue()}: ${safe(tail)}", true)
        }
    }

    companion object {
        fun availableModels(config: WorkerConfig): Set<String> {
            if (config.whisperModels.isEmpty()) return emptySet()
            if (!config.whisperBinary.isExecutable() || !config.ffmpegBinary.isExecutable()) {
                System.err.println("Local transcription disabled: whisper-cli or ffmpeg is not executable.")
                return emptySet()
            }
            return config.whisperModels.filterValues { path -> path.isRegularFile().also { if (!it) System.err.println("Whisper model file is missing: $path") } }.keys
        }
    }
}
