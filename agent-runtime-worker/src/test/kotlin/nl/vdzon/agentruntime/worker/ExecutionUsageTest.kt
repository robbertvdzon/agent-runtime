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

    @Test fun `transcript can be tailed repeatedly without blocking provider usage on failed log delivery`(@TempDir root: Path) {
        ExecutionTranscript(root.resolve("transcript.log")).use { transcript ->
            val lines = mutableListOf<String>()
            transcript.append("eerste regel")
            transcript.forward { lines += it }
            transcript.append("tweede regel één")
            transcript.forward { lines += it }
            transcript.append("logverbinding weg")
            transcript.forward { throw IOException("offline") }
            transcript.append("uitvoering gaat door")
            transcript.forward { lines += it }
            assertThat(lines).containsExactly("eerste regel", "tweede regel één", "uitvoering gaat door")
        }
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
