package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.server.v2.openAiReasoningOptions
import nl.vdzon.agentruntime.server.v2.openAiUsageMetrics
import nl.vdzon.agentruntime.contracts.v2.UsageMetric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAiRequestOptionsTest {
    @Test
    fun `requests reasoning summaries with the current Responses API field`() {
        val options = openAiReasoningOptions(ObjectMapper())

        assertThat(options.path("summary").asText()).isEqualTo("auto")
        assertThat(options.has("generate_summary")).isFalse()
    }

    @Test
    fun `separates cached tokens from normally priced input tokens`() {
        val response = ObjectMapper().readTree(
            """{"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":40},"output_tokens":20,"output_tokens_details":{"reasoning_tokens":5}}}"""
        )

        val metrics = openAiUsageMetrics(response).associate { it.metric to it.quantity }

        assertThat(metrics[UsageMetric.INPUT_TOKENS]).isEqualTo("60")
        assertThat(metrics[UsageMetric.CACHED_INPUT_TOKENS]).isEqualTo("40")
        assertThat(metrics[UsageMetric.OUTPUT_TOKENS]).isEqualTo("20")
        assertThat(metrics[UsageMetric.REASONING_TOKENS]).isEqualTo("5")
    }
}
