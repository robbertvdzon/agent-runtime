package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir

class ExecutionUsageTest {
    private val mapper = jacksonObjectMapper()

    @Test fun `Ollama explicit handoff cannot report incomplete research usage as complete after formatting`() {
        val tracker = ProviderUsageTracker("ollama", mapper)
        tracker.observe("""{"type":"ar.ollama.usage_incomplete"}""")
        tracker.observe("""{"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":20}}""")
        assertThat(tracker.complete).isFalse()
        assertThat(tracker.totals()[UsageMetric.INPUT_TOKENS]).isEqualTo(100L)
    }

    @Test fun `Mistral accounts for every request including hook retries and deduplicates journal replays`() {
        val requests = mutableListOf<AppendUsageRequest>()
        val reporter = ExecutionUsageReporter("fence", { requests += it })
        val round = reporter.round("mistral", mapper)
        val first = """{"type":"mistral.usage","request_id":"first","session_id":"session","usage":{"input_tokens":100,"cached_input_tokens":60,"output_tokens":10}}"""
        round.observe(first)
        round.observe(first)
        round.observe("""{"type":"mistral.usage","request_id":"repair","session_id":"session","usage":{"input_tokens":200,"cached_input_tokens":150,"output_tokens":20}}""")
        round.observe("""{"type":"mistral.completed","session_id":"session","complete":true}""")
        round.finish(9999, 9999)
        reporter.finish()
        fun total(metric: UsageMetric) = requests.flatMap { it.metrics }.filter { it.metric == metric }.sumOf { it.quantity.toLong() }
        assertThat(total(UsageMetric.INPUT_TOKENS)).isEqualTo(90L)
        assertThat(total(UsageMetric.CACHED_INPUT_TOKENS)).isEqualTo(210L)
        assertThat(total(UsageMetric.OUTPUT_TOKENS)).isEqualTo(30L)
        assertThat(requests.dropLast(1).map { it.providerRequestId }.toSet()).containsExactly("session")
        assertThat(requests.last().complete).isTrue()
    }

    @Test fun `transcript can be tailed repeatedly without blocking provider usage on failed log delivery`(@TempDir root: Path) {
        ExecutionTranscript(root.resolve("transcript.log")).use { transcript ->
            val lines = mutableListOf<String>()
            transcript.append("eerste regel")
            transcript.forward { lines += it.text }
            transcript.append("tweede regel één")
            transcript.forward { lines += it.text }
            transcript.append("logverbinding weg")
            transcript.forward { throw IOException("offline") }
            transcript.append("uitvoering gaat door")
            transcript.forward { lines += it.text }
            assertThat(lines).containsExactly("eerste regel", "tweede regel één", "logverbinding weg", "uitvoering gaat door")
        }
    }

    @Test fun `long Claude event retains complete JSON and a stable retry identity`(@TempDir root: Path) {
        val text = "lange tekst ".repeat(2000)
        val event = mapper.writeValueAsString(mapOf("type" to "assistant", "message" to mapOf("id" to "m1", "content" to listOf(mapOf("type" to "text", "text" to text)))))
        ExecutionTranscript(root.resolve("transcript.log"), 3).use { transcript ->
            transcript.append(redactProviderEvent(event, mapper))
            val requests = mutableListOf<TranscriptChunk>()
            transcript.forward { requests += it; throw IOException("lost acknowledgement") }
            transcript.forward { requests += it }
            assertThat(requests[1]).isEqualTo(requests[0])
            val chunks = requests.distinctBy { it.eventId }
            assertThat(chunks).hasSizeGreaterThan(1)
            assertThat(chunks.map { it.streamId }.distinct()).hasSize(1)
            assertThat(chunks.first().streamId).startsWith("provider-event:3:")
            assertThat(chunks.all { it.text.length <= 8192 }).isTrue()
            assertThat(chunks.dropLast(1).none { it.final }).isTrue()
            assertThat(chunks.last().final).isTrue()
            assertThat(mapper.readTree(chunks.joinToString("") { it.text }).path("message").path("content")[0].path("text").asText()).isEqualTo(text)
        }
    }

    @Test fun `redaction precedes chunk boundaries and decodes escaped secrets`(@TempDir root: Path) {
        val secret = "secret-quote-\"-slash-\\-emoji-😀"
        SecretRedactor.configure(listOf(secret))
        try {
            val prefix = " ".repeat(20000)
            val event = mapper.writeValueAsString(mapOf("type" to "assistant", "text" to prefix + secret + " 😀 end", "password" to "unknown-sensitive-value"))
                .replace("secret-quote", "\\u0073ecret-quote")
            ExecutionTranscript(root.resolve("transcript.log")).use { transcript ->
                transcript.append(redactProviderEvent(event, mapper))
                val chunks = mutableListOf<TranscriptChunk>()
                transcript.forward { chunks += it }
                val reconstructed = chunks.joinToString("") { it.text }
                assertThat(chunks.any { it.text.isNotEmpty() && it.text.isBlank() }).isTrue()
                assertThat(reconstructed).doesNotContain("secret-quote", "unknown-sensitive-value")
                assertThat(mapper.readTree(reconstructed).path("text").asText()).isEqualTo(prefix + "[REDACTED] 😀 end")
                assertThat(mapper.readTree(reconstructed).path("password").asText()).isEqualTo("[REDACTED]")
            }
        } finally { SecretRedactor.configure(emptyList()) }
    }

    @Test fun `chunk boundaries preserve supplementary Unicode and standalone logs`(@TempDir root: Path) {
        val line = "x".repeat(8191) + "😀" + "y"
        ExecutionTranscript(root.resolve("transcript.log")).use { transcript ->
            transcript.append(line)
            transcript.append(redactProviderEvent("Docker image is up to date", mapper))
            val chunks = mutableListOf<TranscriptChunk>()
            transcript.forward { chunks += it }
            assertThat(chunks).hasSize(3)
            assertThat(chunks.first().text).hasSize(8191)
            assertThat(chunks.take(2).joinToString("") { String(it.text.toByteArray()) }).isEqualTo(line)
            assertThat(chunks.last().text).isEqualTo("Docker image is up to date")
            assertThat(chunks.last().final).isTrue()
            assertThat(chunks.last().streamId).isNotEqualTo(chunks.first().streamId)
        }
    }

    @Test fun `slow log delivery does not block producer and drains when closed`(@TempDir root: Path) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val chunks = java.util.concurrent.CopyOnWriteArrayList<TranscriptChunk>()
        ExecutionTranscript(root.resolve("transcript.log")).use { transcript ->
            transcript.startForwarding { chunk -> entered.countDown(); release.await(); chunks += chunk }
            transcript.append("first")
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            transcript.append("second")
            assertThat(chunks).isEmpty()
            release.countDown()
        }
        assertThat(chunks.map { it.text }).containsExactly("first", "second")
    }

    @Test fun `Codex cached input is separate and repeated summaries do not double count`() {
        val tracker = ProviderUsageTracker("openai", mapper)
        repeat(2) { tracker.observe("""{"type":"turn.completed","usage":{"input_tokens":1000,"cached_input_tokens":800,"output_tokens":50,"reasoning_output_tokens":20}}""") }
        assertThat(tracker.totals()).containsEntry(UsageMetric.INPUT_TOKENS, 200L)
            .containsEntry(UsageMetric.CACHED_INPUT_TOKENS, 800L).containsEntry(UsageMetric.OUTPUT_TOKENS, 50L)
        assertThat(tracker.isComplete()).isTrue()
    }

    @Test fun `Claude streaming usage survives cancellation before a final result`() {
        val tracker = ProviderUsageTracker("anthropic", mapper)
        tracker.observe("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":100,"cache_read_input_tokens":500,"cache_creation_input_tokens":20,"output_tokens":1}}}}""")
        repeat(2) { tracker.observe("""{"type":"assistant","message":{"id":"m1","usage":{"input_tokens":100,"cache_read_input_tokens":500,"cache_creation_input_tokens":20,"output_tokens":1}}}""") }
        tracker.observe("""{"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":30}}}""")
        assertThat(tracker.totals()).containsEntry(UsageMetric.INPUT_TOKENS, 100L)
            .containsEntry(UsageMetric.CACHED_INPUT_TOKENS, 500L).containsEntry(UsageMetric.CACHE_WRITE_TOKENS, 20L)
            .containsEntry(UsageMetric.OUTPUT_TOKENS, 30L)
        assertThat(tracker.isComplete()).isFalse()
        tracker.observe("""{"type":"result","subtype":"error_during_execution","is_error":true,"usage":{"input_tokens":0,"output_tokens":0}}""")
        assertThat(tracker.totals()[UsageMetric.OUTPUT_TOKENS]).isEqualTo(30L)
    }

    @Test fun `Claude totals reconcile multiple messages without adding the final total again`() {
        val tracker = ProviderUsageTracker("anthropic", mapper)
        tracker.observe("""{"type":"assistant","message":{"id":"m1","usage":{"input_tokens":100,"output_tokens":1}}}""")
        tracker.observe("""{"type":"assistant","message":{"id":"m2","usage":{"input_tokens":200,"output_tokens":1}}}""")
        tracker.observe("""{"type":"result","subtype":"success","is_error":false,"usage":{"input_tokens":300,"output_tokens":50}}""")
        assertThat(tracker.totals()).containsEntry(UsageMetric.INPUT_TOKENS, 300L).containsEntry(UsageMetric.OUTPUT_TOKENS, 50L)
        assertThat(tracker.isComplete()).isTrue()
        tracker.observe("""{"type":"assistant","parent_tool_use_id":"tool-1","message":{"id":"nested","usage":{"input_tokens":900}}}""")
        assertThat(tracker.totals()[UsageMetric.INPUT_TOKENS]).isEqualTo(300L)
        assertThat(tracker.isComplete()).isFalse()
    }

    @Test fun `usage is queued during execution and replays an identical request after lost acknowledgement`() {
        val requests = mutableListOf<AppendUsageRequest>()
        val reporter = ExecutionUsageReporter("test-fence", { request ->
            requests += request
            if (requests.size == 1) throw IOException("lost acknowledgement")
        })
        val first = reporter.round("openai", mapper)
        first.observe("""{"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":20}}""")
        reporter.flush()
        assertThat(requests).hasSize(1) // before finish/result validation
        first.finish(9999, 9999) // provider metrics supersede the estimate
        val second = reporter.round("openai", mapper)
        second.observe("""{"type":"turn.completed","usage":{"input_tokens":200,"output_tokens":30}}""")
        second.finish(9999, 9999)
        reporter.finish()
        assertThat(requests[1]).isEqualTo(requests[0])
        val unique = requests.distinctBy { it.eventId }
        assertThat(unique.flatMap { it.metrics }.filter { it.metric == UsageMetric.INPUT_TOKENS }.sumOf { it.quantity.toLong() }).isEqualTo(300L)
        assertThat(unique.flatMap { it.metrics }.filter { it.metric == UsageMetric.OUTPUT_TOKENS }.sumOf { it.quantity.toLong() }).isEqualTo(50L)
        assertThat(unique.last().complete).isTrue()
    }

    @Test fun `legacy malformed output is estimated per round while missing telemetry is never invented`() {
        val requests = mutableListOf<AppendUsageRequest>()
        val reporter = ExecutionUsageReporter("test-fence", { requests += it })
        val round = reporter.round("openai", mapper)
        round.observe("Provider output is not valid JSON")
        round.finish(100, 20)
        reporter.round("openai", mapper).finish(100, 0)
        reporter.finish()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().source).isEqualTo(UsageSource.WORKER_MEASURED)
        assertThat(requests.single().complete).isFalse()
    }
}
