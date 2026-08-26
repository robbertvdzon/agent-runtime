package nl.vdzon.agentruntime.server.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import nl.vdzon.agentruntime.contracts.JsonValidationError
import nl.vdzon.agentruntime.server.config.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

data class CandidateValidation(
    val result: JsonNode?,
    val errorCode: String?,
    val errors: List<JsonValidationError>,
    val normalizedText: String,
)

@Service
class JsonResultValidator(private val mapper: ObjectMapper) {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val forbiddenKeywords = setOf(
        "\$ref", "\$dynamicRef", "allOf", "anyOf", "oneOf", "not", "if", "then", "else",
        "contains", "dependentSchemas", "patternProperties", "unevaluatedProperties", "unevaluatedItems",
    )

    fun validateSchema(schema: JsonNode?) {
        if (schema == null) return
        if (!schema.isObject) throw ApiException("RESPONSE_SCHEMA_INVALID", "Response schema must be a JSON object.")
        if (schema.toString().toByteArray().size > 100_000) throw ApiException("RESPONSE_SCHEMA_TOO_LARGE", "Response schema exceeds 100 KB.", HttpStatus.PAYLOAD_TOO_LARGE)
        val forbidden = findForbidden(schema)
        if (forbidden != null) throw ApiException("RESPONSE_SCHEMA_UNSUPPORTED", "Response schema keyword $forbidden is not in the portable profile.")
        runCatching { registry.getSchema(schema) }.getOrElse {
            throw ApiException("RESPONSE_SCHEMA_INVALID", "Response schema cannot be compiled.")
        }
    }

    fun validate(schema: JsonNode?, value: JsonNode): List<JsonValidationError> {
        if (schema == null) return emptyList()
        return registry.getSchema(schema).validate(value)
            .map { error ->
                JsonValidationError(
                    path = error.instanceLocation.toString().ifBlank { "$" },
                    keyword = error.keyword ?: error.messageKey ?: "schema",
                    message = error.message.take(500),
                )
            }
            .sortedWith(compareBy(JsonValidationError::path, JsonValidationError::keyword, JsonValidationError::message))
            .take(25)
    }

    fun validateCandidate(schema: JsonNode?, candidate: String): CandidateValidation {
        if (candidate.toByteArray().size > 5 * 1024 * 1024) {
            return CandidateValidation(null, "RESULT_TOO_LARGE", listOf(JsonValidationError("$", "maxSize", "Result exceeds 5 MB.")), candidate.take(2_000))
        }
        val normalized = normalize(candidate)
        val parsed = runCatching { mapper.readTree(normalized) }.getOrNull()
            ?: return CandidateValidation(null, "MODEL_OUTPUT_NOT_JSON", listOf(JsonValidationError("$", "json", "Result is not syntactically valid JSON.")), normalized)
        val errors = validate(schema, parsed)
        return if (errors.isEmpty()) CandidateValidation(parsed, null, emptyList(), normalized)
        else CandidateValidation(null, "MODEL_OUTPUT_SCHEMA_INVALID", errors, normalized)
    }

    fun normalize(candidate: String): String {
        val trimmed = candidate.trim()
        val match = JSON_BLOCK.matchEntire(trimmed) ?: return trimmed
        return match.groupValues[1].trim()
    }

    private fun findForbidden(node: JsonNode): String? {
        if (node.isObject) {
            val fields = node.fields()
            while (fields.hasNext()) {
                val (name, child) = fields.next()
                if (name in forbiddenKeywords) return name
                findForbidden(child)?.let { return it }
            }
        } else if (node.isArray) {
            val elements = node.elements()
            while (elements.hasNext()) findForbidden(elements.next())?.let { return it }
        }
        return null
    }

    companion object {
        private val JSON_BLOCK = Regex("(?s)(?:Here is the JSON:?\\s*)?```json\\s*(.*?)\\s*```")
    }
}
