package nl.vdzon.agentruntime.server.jobs

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.JsonValidationError
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class OutputAttemptRecord(
    val id: String,
    val jobId: String,
    val executionAttemptId: String?,
    val number: Int,
    val idempotencyKey: String,
    val status: String,
    val validationErrors: List<JsonValidationError>,
    val diagnosticExcerpt: String?,
    val acceptedResult: com.fasterxml.jackson.databind.JsonNode?,
    val usage: com.fasterxml.jackson.databind.JsonNode?,
    val provider: String,
    val model: String,
    val startedAt: Instant,
    val completedAt: Instant?,
)

@Repository
class OutputAttemptStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun findByIdempotency(jobId: String, key: String): OutputAttemptRecord? = one(
        "SELECT * FROM runtime_output_attempt WHERE job_id=? AND idempotency_key=?", jobId, key,
    )

    fun find(id: String): OutputAttemptRecord? = one("SELECT * FROM runtime_output_attempt WHERE id=?", id)

    fun count(jobId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM runtime_output_attempt WHERE job_id=?", Int::class.java, jobId,
    ) ?: 0

    fun start(job: StoredJob, executionAttemptId: String?, idempotencyKey: String): OutputAttemptRecord {
        val id = UUID.randomUUID().toString()
        val number = count(job.view.id) + 1
        jdbc.update(
            """INSERT INTO runtime_output_attempt
               (id,job_id,execution_attempt_id,output_attempt_number,idempotency_key,status,provider,model,started_at)
               VALUES (?,?,?,?,?,'RESERVED',?,?,?)""",
            id, job.view.id, executionAttemptId, number, idempotencyKey, job.view.provider.name,
            job.view.model, Instant.now().atOffset(ZoneOffset.UTC),
        )
        return find(id)!!
    }

    fun reject(id: String, sha256: String, excerpt: String, errors: List<JsonValidationError>) {
        jdbc.update(
            """UPDATE runtime_output_attempt SET status='REJECTED',candidate_sha256=?,diagnostic_excerpt=?,
               validation_errors_json=?,completed_at=? WHERE id=? AND status='RESERVED'""",
            sha256, excerpt.take(2_000), mapper.writeValueAsString(errors), Instant.now().atOffset(ZoneOffset.UTC), id,
        )
    }

    fun accept(id: String, sha256: String, result: com.fasterxml.jackson.databind.JsonNode, usage: com.fasterxml.jackson.databind.JsonNode?) {
        jdbc.update(
            "UPDATE runtime_output_attempt SET status='ACCEPTED',candidate_sha256=?,accepted_result_json=?,usage_json=?,completed_at=? WHERE id=? AND status='RESERVED'",
            sha256, result.toString(), usage?.toString(), Instant.now().atOffset(ZoneOffset.UTC), id,
        )
    }

    fun abandonReservedForExecution(executionAttemptId: String) {
        jdbc.update(
            "UPDATE runtime_output_attempt SET status='ABANDONED',completed_at=? WHERE execution_attempt_id=? AND status='RESERVED'",
            Instant.now().atOffset(ZoneOffset.UTC), executionAttemptId,
        )
    }

    fun list(jobId: String): List<OutputAttemptRecord> = jdbc.query(
        "SELECT * FROM runtime_output_attempt WHERE job_id=? ORDER BY output_attempt_number", rowMapper, jobId,
    )

    private fun one(sql: String, vararg args: Any): OutputAttemptRecord? = try {
        jdbc.queryForObject(sql, rowMapper, *args)
    } catch (_: EmptyResultDataAccessException) { null }

    private val rowMapper = org.springframework.jdbc.core.RowMapper<OutputAttemptRecord> { rs, _ ->
        OutputAttemptRecord(
            rs.getString("id"), rs.getString("job_id"), rs.getString("execution_attempt_id"),
            rs.getInt("output_attempt_number"), rs.getString("idempotency_key"), rs.getString("status"),
            rs.getString("validation_errors_json")?.let {
                mapper.readValue(it, object : TypeReference<List<JsonValidationError>>() {})
            } ?: emptyList(),
            rs.getString("diagnostic_excerpt"),
            rs.getString("accepted_result_json")?.let(mapper::readTree),
            rs.getString("usage_json")?.let(mapper::readTree),
            rs.getString("provider"), rs.getString("model"),
            rs.getObject("started_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }
}
