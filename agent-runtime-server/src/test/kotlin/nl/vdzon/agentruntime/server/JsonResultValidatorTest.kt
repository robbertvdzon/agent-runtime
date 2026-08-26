package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.jobs.JsonResultValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JsonResultValidatorTest {
    private val mapper = jacksonObjectMapper()
    private val validator = JsonResultValidator(mapper)

    @Test
    fun `draft portable profile validates nested arrays and boundaries`() {
        val schema = mapper.readTree("""{"type":"object","required":["items"],"additionalProperties":false,"properties":{"items":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"object","required":["score"],"properties":{"score":{"type":"number","minimum":0,"maximum":10}}}}}}""")
        validator.validateSchema(schema)
        assertThat(validator.validateCandidate(schema, """{"items":[{"score":7}]}""").result).isNotNull
        val invalid = validator.validateCandidate(schema, """{"items":[{"score":12}],"extra":true}""")
        assertThat(invalid.result).isNull()
        assertThat(invalid.errors.map { it.path }).isSorted
    }

    @Test
    fun `one json code block is normalized but free prose is not searched`() {
        assertThat(validator.validateCandidate(null, "Here is the JSON:\n```json\n{\"ok\":true}\n```").result).isNotNull
        assertThat(validator.validateCandidate(null, "prose {\"ok\":true}").errorCode).isEqualTo("MODEL_OUTPUT_NOT_JSON")
    }

    @Test
    fun `references and combinators are rejected at job creation`() {
        assertThatThrownBy { validator.validateSchema(mapper.readTree("""{"type":"object","properties":{"x":{"${'$'}ref":"https://example.org/schema"}}}""")) }
            .isInstanceOf(ApiException::class.java)
        assertThatThrownBy { validator.validateSchema(mapper.readTree("""{"oneOf":[{"type":"string"}]}""")) }
            .isInstanceOf(ApiException::class.java)
    }
}
