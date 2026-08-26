package nl.vdzon.agentruntime.server.jobs

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

data class StoredInputAttachment(
    val id: String,
    val jobId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val content: ByteArray,
    val createdAt: Instant,
)

@Repository
class InputAttachmentStore(private val jdbc: JdbcTemplate) {
    fun insert(jobId: String, filename: String, mimeType: String, content: ByteArray) {
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        jdbc.update(
            """INSERT INTO runtime_input_attachment
               (id,job_id,filename,mime_type,size_bytes,sha256,content,created_at)
               VALUES (?,?,?,?,?,?,?,?)""",
            UUID.randomUUID().toString(), jobId, filename, mimeType, content.size.toLong(), sha256,
            content, Instant.now().atOffset(ZoneOffset.UTC),
        )
    }

    fun list(jobId: String): List<StoredInputAttachment> = jdbc.query(
        "SELECT id,job_id,filename,mime_type,size_bytes,sha256,content,created_at FROM runtime_input_attachment WHERE job_id=? ORDER BY filename",
        { rs, _ -> map(rs) },
        jobId,
    )

    fun count(jobId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM runtime_input_attachment WHERE job_id=?", Int::class.java, jobId,
    ) ?: 0

    fun find(id: String): StoredInputAttachment? = try {
        jdbc.queryForObject(
            "SELECT id,job_id,filename,mime_type,size_bytes,sha256,content,created_at FROM runtime_input_attachment WHERE id=?",
            { rs, _ -> map(rs) }, id,
        )
    } catch (_: EmptyResultDataAccessException) { null }

    private fun map(rs: java.sql.ResultSet) = StoredInputAttachment(
        rs.getString("id"), rs.getString("job_id"), rs.getString("filename"), rs.getString("mime_type"),
        rs.getLong("size_bytes"), rs.getString("sha256"), rs.getBytes("content"),
        rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
    )
}
