package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.server.v2.openAiReasoningOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAiRequestOptionsTest {
    @Test
    fun `requests reasoning summaries with the current Responses API field`() {
        val options = openAiReasoningOptions(ObjectMapper())

        assertThat(options.path("summary").asText()).isEqualTo("auto")
        assertThat(options.has("generate_summary")).isFalse()
    }
}
