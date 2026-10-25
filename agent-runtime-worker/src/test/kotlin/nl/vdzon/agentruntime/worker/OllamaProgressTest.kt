package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OllamaProgressTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `only declared Ollama stages become bounded progress updates`() {
        assertThat(ollamaProgress("""{"type":"ar.ollama.phase","stage":"repair","attempt":2}""", mapper))
            .isEqualTo(OllamaProgress("REPAIRING", 73, "Antwoord herstellen (2/3), zonder bronnen opnieuw te lezen."))
        assertThat(ollamaProgress("""{"type":"ar.ollama.phase","stage":"research","attempt":2}""", mapper)?.message)
            .contains("Gericht vervolgonderzoek")
        assertThat(ollamaProgress("""{"type":"ar.ollama.phase","stage":"restart","attempt":2}""", mapper)?.message)
            .contains("Geen bruikbaar antwoord")
        for (line in listOf("not json", """{"type":"turn.completed"}""",
            """{"type":"ar.ollama.phase","stage":"COMPLETED"}""",
            """{"type":"ar.ollama.phase","stage":"repair","attempt":99}""")) {
            assertThat(ollamaProgress(line, mapper)).isNull()
        }
    }
}
