package nl.vdzon.agentruntime.server.jobs

import nl.vdzon.agentruntime.contracts.TranscriptKind
import nl.vdzon.agentruntime.contracts.TranscriptPartView
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class TranscriptStore(private val jdbc: JdbcTemplate) {
    fun totalBytes(jobId: String): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(OCTET_LENGTH(text_value)),0) FROM runtime_transcript WHERE job_id=?", Long::class.java, jobId,
    ) ?: 0

    fun find(attemptId: String, partId: String): TranscriptPartView? = byPart(attemptId, partId)

    fun append(jobId: String, attemptId: String, partId: String, sequence: Long, kind: TranscriptKind, text: String, redacted: Boolean): TranscriptPartView {
        try {
            jdbc.update(
                """INSERT INTO runtime_transcript(job_id,attempt_id,part_id,sequence_number,kind,text_value,redacted,created_at)
                   VALUES (?,?,?,?,?,?,?,?)""",
                jobId, attemptId, partId, sequence, kind.name, text, redacted, Instant.now().atOffset(ZoneOffset.UTC),
            )
        } catch (_: DuplicateKeyException) {
            val existing = byPart(attemptId, partId)
                ?: throw IllegalStateException("Transcript sequence conflicts with another part.")
            require(existing.sequence == sequence && existing.kind == kind && existing.text == text) { "Transcript idempotency conflict." }
            return existing
        }
        return byPart(attemptId, partId)!!
    }

    fun page(jobId: String, afterSequence: Long?, beforeSequence: Long?, limit: Int): List<TranscriptPartView> {
        val bounded = limit.coerceIn(1, 200)
        return when {
            beforeSequence != null -> jdbc.query(
                "SELECT * FROM runtime_transcript WHERE job_id=? AND sequence_number<? ORDER BY sequence_number DESC LIMIT ?",
                mapper, jobId, beforeSequence, bounded,
            ).reversed()
            else -> jdbc.query(
                "SELECT * FROM runtime_transcript WHERE job_id=? AND sequence_number>? ORDER BY sequence_number ASC LIMIT ?",
                mapper, jobId, afterSequence ?: 0, bounded,
            )
        }
    }

    private fun byPart(attemptId: String, partId: String): TranscriptPartView? = jdbc.query(
        "SELECT * FROM runtime_transcript WHERE attempt_id=? AND part_id=?", mapper, attemptId, partId,
    ).firstOrNull()

    private val mapper = org.springframework.jdbc.core.RowMapper<TranscriptPartView> { rs, _ ->
        TranscriptPartView(
            rs.getString("job_id"), rs.getString("attempt_id"), rs.getString("part_id"),
            rs.getLong("sequence_number"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            TranscriptKind.valueOf(rs.getString("kind")), rs.getString("text_value"), rs.getBoolean("redacted"),
        )
    }
}
