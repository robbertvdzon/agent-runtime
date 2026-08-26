package nl.vdzon.agentruntime.server.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.*
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class StoredJob(
    val view: JobView,
    val request: CreateJobRequest,
    val result: JsonNode?,
    val usage: JsonNode?,
    val completedAt: Instant?,
    val cancelRequested: Boolean,
    val cancelledAt: Instant?,
    val cancelledBy: String?,
)

@Repository
class JobStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val rowMapper = RowMapper<StoredJob> { rs, _ -> mapJob(rs) }

    fun insert(tenantId: String, request: CreateJobRequest, maxAttempts: Int, priority: Int): StoredJob {
        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        jdbc.update(
            """INSERT INTO runtime_job
               (id, tenant_id, idempotency_key, job_kind, provider, model,
                request_json, response_schema_json, status, phase, max_attempts, priority, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 'QUEUED', ?, ?, ?, ?)""",
            id, tenantId, request.idempotencyKey, request.jobKind.name,
            request.provider.name, request.model, mapper.writeValueAsString(request), request.responseSchema?.toString(),
            maxAttempts, priority, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
        )
        addEvent(id, "JOB_CREATED", "QUEUED", "Job accepted for durable processing.")
        return find(id)!!
    }

    fun findByIdempotency(tenantId: String, key: String): StoredJob? = one(
        "SELECT * FROM runtime_job WHERE tenant_id = ? AND idempotency_key = ?", tenantId, key
    )

    fun find(id: String): StoredJob? = one("SELECT * FROM runtime_job WHERE id = ?", id)

    fun list(tenantId: String?, status: JobStatus?, limit: Int = 200): List<StoredJob> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (tenantId != null) { clauses += "tenant_id = ?"; args += tenantId }
        if (status != null) { clauses += "status = ?"; args += status.name }
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        args += limit.coerceIn(1, 5_000)
        return jdbc.query("SELECT * FROM runtime_job$where ORDER BY created_at DESC LIMIT ?", rowMapper, *args.toTypedArray())
    }

    fun queued(): List<StoredJob> = jdbc.query(
        """SELECT * FROM runtime_job
           WHERE status IN ('QUEUED','WAITING_FOR_WORKER') AND (not_before IS NULL OR not_before <= CURRENT_TIMESTAMP)
           ORDER BY priority DESC, created_at ASC""", rowMapper
    )

    fun events(jobId: String): List<JobEventView> = jdbc.query(
        "SELECT * FROM runtime_event WHERE job_id = ? ORDER BY id",
        { rs, _ -> JobEventView(rs.getLong("id"), jobId, rs.getString("event_type"), rs.getString("phase"), rs.getString("message"), instant(rs, "created_at")!!) },
        jobId,
    )

    fun addEvent(jobId: String, type: String, phase: String, message: String?) {
        jdbc.update(
            "INSERT INTO runtime_event(job_id, event_type, phase, message, created_at) VALUES (?, ?, ?, ?, ?)",
            jobId, type, phase, message?.take(1000), Instant.now().atOffset(ZoneOffset.UTC),
        )
    }

    fun markWaiting(jobId: String) {
        jdbc.update("UPDATE runtime_job SET status='WAITING_FOR_WORKER', phase='WAITING_FOR_WORKER', updated_at=? WHERE id=? AND status='QUEUED'", Instant.now().atOffset(ZoneOffset.UTC), jobId)
    }

    fun markRunning(jobId: String, attemptCount: Int) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update(
            """UPDATE runtime_job SET status='RUNNING', phase='LEASED', attempt_count=?, not_before=NULL,
               progress_percent=NULL, progress_message=NULL, error_code=NULL, error_message=NULL, updated_at=? WHERE id=?""",
            attemptCount, now, jobId,
        )
        addEvent(jobId, "JOB_LEASED", "LEASED", "Worker claimed attempt $attemptCount.")
    }

    fun progress(jobId: String, phase: String, percent: Int?, message: String?) {
        jdbc.update(
            "UPDATE runtime_job SET phase=?, progress_percent=?, progress_message=?, updated_at=? WHERE id=? AND status='RUNNING'",
            phase, percent, message?.take(1000), Instant.now().atOffset(ZoneOffset.UTC), jobId,
        )
        addEvent(jobId, "PROGRESS", phase, message)
    }

    fun complete(jobId: String, result: JsonNode, usage: JsonNode?) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update(
            """UPDATE runtime_job SET status='SUCCEEDED', phase='COMPLETED', result_json=?, usage_json=?,
               completed_at=?, updated_at=? WHERE id=? AND status='RUNNING'""",
            result.toString(), usage?.toString(), now, now, jobId,
        )
        addEvent(jobId, "JOB_SUCCEEDED", "COMPLETED", "Job completed successfully.")
    }

    fun completeMock(jobId: String, result: JsonNode) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update(
            """UPDATE runtime_job SET status='SUCCEEDED', phase='COMPLETED', result_json=?,
               completed_at=?, updated_at=? WHERE id=? AND status IN ('QUEUED','WAITING_FOR_WORKER')""",
            result.toString(), now, now, jobId,
        )
        addEvent(jobId, "MOCK_JOB_SUCCEEDED", "COMPLETED", "Prepared server-side mock response consumed without a worker attempt.")
    }

    fun fail(jobId: String, code: String, message: String) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update(
            """UPDATE runtime_job SET status='FAILED', phase='FAILED', error_code=?, error_message=?,
               completed_at=?, updated_at=? WHERE id=? AND status NOT IN ('SUCCEEDED','CANCELLED')""",
            code.take(120), message.take(2000), now, now, jobId,
        )
        addEvent(jobId, "JOB_FAILED", "FAILED", "$code: ${message.take(800)}")
    }

    fun retry(jobId: String, code: String, message: String, notBefore: Instant) {
        jdbc.update(
            """UPDATE runtime_job SET status='QUEUED', phase='RETRY_WAIT', error_code=?, error_message=?,
               not_before=?, updated_at=? WHERE id=? AND status='RUNNING'""",
            code.take(120), message.take(2000), notBefore.atOffset(ZoneOffset.UTC), Instant.now().atOffset(ZoneOffset.UTC), jobId,
        )
        addEvent(jobId, "RETRY_SCHEDULED", "RETRY_WAIT", "Retry is available at $notBefore after $code.")
    }

    fun requestCancel(jobId: String, actor: String) {
        val stored = find(jobId) ?: return
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        if (stored.view.status == JobStatus.QUEUED || stored.view.status == JobStatus.WAITING_FOR_WORKER) {
            jdbc.update("UPDATE runtime_job SET status='CANCELLED', phase='CANCELLED', cancel_requested=TRUE, cancelled_at=?, cancelled_by=?, completed_at=?, updated_at=? WHERE id=?", now, actor.take(160), now, now, jobId)
            addEvent(jobId, "JOB_CANCELLED", "CANCELLED", "Job cancelled before execution.")
        } else if (stored.view.status == JobStatus.RUNNING) {
            jdbc.update("UPDATE runtime_job SET cancel_requested=TRUE, updated_at=? WHERE id=?", now, jobId)
            addEvent(jobId, "CANCELLATION_REQUESTED", stored.view.phase, "Cancellation requested; worker will stop the active process.")
        }
    }

    fun markCancelled(jobId: String, message: String, actor: String) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update("UPDATE runtime_job SET status='CANCELLED', phase='CANCELLED', cancelled_at=?, cancelled_by=?, completed_at=?, updated_at=? WHERE id=?", now, actor.take(160), now, now, jobId)
        addEvent(jobId, "JOB_CANCELLED", "CANCELLED", message)
    }

    fun resetForAdminRetry(jobId: String) {
        jdbc.update(
            """UPDATE runtime_job SET status='QUEUED', phase='QUEUED', attempt_count=0, cancel_requested=FALSE,
               result_json=NULL, usage_json=NULL, error_code=NULL, error_message=NULL, completed_at=NULL,
               cancelled_at=NULL, cancelled_by=NULL, not_before=NULL, updated_at=? WHERE id=? AND status IN ('FAILED','CANCELLED')""",
            Instant.now().atOffset(ZoneOffset.UTC), jobId,
        )
        addEvent(jobId, "ADMIN_RETRY", "QUEUED", "Administrator requested a new execution cycle.")
    }

    fun artifacts(jobId: String): List<ArtifactView> = jdbc.query(
        "SELECT id, job_id, filename, mime_type, size_bytes, sha256, created_at FROM runtime_artifact WHERE job_id=? ORDER BY created_at",
        { rs, _ -> ArtifactView(rs.getString("id"), rs.getString("job_id"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "created_at")!!) },
        jobId,
    )

    fun artifactBytes(id: String): ByteArray? = try {
        jdbc.queryForObject("SELECT content FROM runtime_artifact WHERE id=?", ByteArray::class.java, id)
    } catch (_: EmptyResultDataAccessException) { null }

    fun artifact(id: String): ArtifactView? = try {
        jdbc.queryForObject(
            "SELECT id, job_id, filename, mime_type, size_bytes, sha256, created_at FROM runtime_artifact WHERE id=?",
            { rs, _ -> ArtifactView(rs.getString("id"), rs.getString("job_id"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "created_at")!!) }, id,
        )
    } catch (_: EmptyResultDataAccessException) { null }

    fun totalArtifactBytes(jobId: String): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(size_bytes),0) FROM runtime_artifact WHERE job_id=?", Long::class.java, jobId
    ) ?: 0

    fun artifactCount(jobId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM runtime_artifact WHERE job_id=?", Int::class.java, jobId
    ) ?: 0

    fun insertArtifact(jobId: String, filename: String, mimeType: String, sha256: String, content: ByteArray): ArtifactView {
        artifactByName(jobId, filename)?.let { existing ->
            if (existing.sha256 != sha256 || existing.sizeBytes != content.size.toLong() || existing.mimeType != mimeType) {
                throw nl.vdzon.agentruntime.server.config.ApiException("ARTIFACT_IDEMPOTENCY_CONFLICT", "Artifact filename already has different immutable content.", org.springframework.http.HttpStatus.CONFLICT)
            }
            return existing
        }
        val id = UUID.randomUUID().toString()
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update(
            "INSERT INTO runtime_artifact(id, job_id, filename, mime_type, size_bytes, sha256, content, created_at) VALUES (?,?,?,?,?,?,?,?)",
            id, jobId, filename.take(255), mimeType.take(160), content.size.toLong(), sha256, content, now,
        )
        addEvent(jobId, "ARTIFACT_UPLOADED", find(jobId)?.view?.phase ?: "RUNNING", "Artifact ${filename.take(200)} uploaded (${content.size} bytes).")
        return artifact(id)!!
    }

    private fun artifactByName(jobId: String, filename: String): ArtifactView? = jdbc.query(
        "SELECT id, job_id, filename, mime_type, size_bytes, sha256, created_at FROM runtime_artifact WHERE job_id=? AND filename=?",
        { rs, _ -> ArtifactView(rs.getString("id"), rs.getString("job_id"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "created_at")!!) },
        jobId, filename,
    ).firstOrNull()

    private fun one(sql: String, vararg args: Any): StoredJob? = try {
        jdbc.queryForObject(sql, rowMapper, *args)
    } catch (_: EmptyResultDataAccessException) { null }

    private fun mapJob(rs: ResultSet): StoredJob {
        val request = mapper.readValue(rs.getString("request_json"), CreateJobRequest::class.java)
        val view = JobView(
            id = rs.getString("id"), tenantId = rs.getString("tenant_id"),
            jobKind = JobKind.valueOf(rs.getString("job_kind")), idempotencyKey = rs.getString("idempotency_key"),
            provider = Provider.valueOf(rs.getString("provider")), model = rs.getString("model"),
            status = JobStatus.valueOf(rs.getString("status")), phase = rs.getString("phase"),
            attemptCount = rs.getInt("attempt_count"), maxAttempts = rs.getInt("max_attempts"),
            priority = rs.getInt("priority"), progressPercent = rs.getObject("progress_percent") as? Int,
            progressMessage = rs.getString("progress_message"), errorCode = rs.getString("error_code"),
            errorMessage = rs.getString("error_message"), createdAt = instant(rs, "created_at")!!,
            updatedAt = instant(rs, "updated_at")!!, notBefore = instant(rs, "not_before"),
        )
        return StoredJob(
            view, request, rs.getString("result_json")?.let(mapper::readTree),
            rs.getString("usage_json")?.let(mapper::readTree), instant(rs, "completed_at"), rs.getBoolean("cancel_requested"),
            instant(rs, "cancelled_at"), rs.getString("cancelled_by")
        )
    }

    companion object {
        fun instant(rs: ResultSet, column: String): Instant? =
            rs.getObject(column, OffsetDateTime::class.java)?.toInstant()
    }
}
