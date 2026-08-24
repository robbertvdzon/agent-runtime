package nl.vdzon.agentruntime.server.workers

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.jobs.JobStore
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

data class AttemptRecord(
    val id: String,
    val jobId: String,
    val workerId: String,
    val workerBootId: String,
    val attemptNumber: Int,
    val tokenHash: String,
    val status: String,
    val leaseUntil: Instant,
    val recoveryUntil: Instant?,
)

@Repository
class WorkerStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun register(request: WorkerRegistrationRequest) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val updated = jdbc.update(
            """UPDATE runtime_worker SET boot_id=?, status='ONLINE', capabilities_json=?, providers_json=?, models_json=?,
               versions_json=?, last_heartbeat_at=? WHERE worker_id=?""",
            request.bootId, mapper.writeValueAsString(request.capabilities), mapper.writeValueAsString(request.providers),
            mapper.writeValueAsString(request.models), mapper.writeValueAsString(request.versions), now, request.workerId,
        )
        if (updated == 0) try {
            jdbc.update(
                """INSERT INTO runtime_worker(worker_id,boot_id,status,capabilities_json,providers_json,models_json,versions_json,last_heartbeat_at,registered_at)
                   VALUES (?,?,'ONLINE',?,?,?,?,?,?)""",
                request.workerId, request.bootId, mapper.writeValueAsString(request.capabilities), mapper.writeValueAsString(request.providers),
                mapper.writeValueAsString(request.models), mapper.writeValueAsString(request.versions), now, now,
            )
        } catch (_: DuplicateKeyException) { register(request) }
    }

    fun heartbeat(workerId: String, bootId: String) {
        jdbc.update("UPDATE runtime_worker SET status='ONLINE', last_heartbeat_at=? WHERE worker_id=? AND boot_id=?", Instant.now().atOffset(ZoneOffset.UTC), workerId, bootId)
    }

    fun createAttempt(jobId: String, workerId: String, bootId: String, attemptNumber: Int, rawToken: String, leaseUntil: Instant): AttemptRecord {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        jdbc.update(
            """INSERT INTO runtime_attempt(id,job_id,worker_id,worker_boot_id,attempt_number,fencing_token_hash,status,lease_until,heartbeat_at,started_at)
               VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?)""",
            id, jobId, workerId, bootId, attemptNumber, hash(rawToken), leaseUntil.atOffset(ZoneOffset.UTC), now, now,
        )
        return findAttempt(id)!!
    }

    fun findAttempt(id: String): AttemptRecord? = try {
        jdbc.queryForObject("SELECT * FROM runtime_attempt WHERE id=?", { rs, _ ->
            AttemptRecord(
                rs.getString("id"), rs.getString("job_id"), rs.getString("worker_id"), rs.getString("worker_boot_id"),
                rs.getInt("attempt_number"), rs.getString("fencing_token_hash"), rs.getString("status"),
                rs.getObject("lease_until", OffsetDateTime::class.java).toInstant(),
                rs.getObject("recovery_until", OffsetDateTime::class.java)?.toInstant(),
            )
        }, id)
    } catch (_: EmptyResultDataAccessException) { null }

    fun activeForJob(jobId: String): AttemptRecord? = try {
        jdbc.queryForObject("SELECT * FROM runtime_attempt WHERE job_id=? AND status IN ('ACTIVE','SUSPECTED') ORDER BY attempt_number DESC LIMIT 1", { rs, _ ->
            AttemptRecord(
                rs.getString("id"), rs.getString("job_id"), rs.getString("worker_id"), rs.getString("worker_boot_id"),
                rs.getInt("attempt_number"), rs.getString("fencing_token_hash"), rs.getString("status"),
                rs.getObject("lease_until", OffsetDateTime::class.java).toInstant(),
                rs.getObject("recovery_until", OffsetDateTime::class.java)?.toInstant(),
            )
        }, jobId)
    } catch (_: EmptyResultDataAccessException) { null }

    fun renew(attemptId: String, leaseUntil: Instant) {
        jdbc.update(
            "UPDATE runtime_attempt SET status='ACTIVE', lease_until=?, recovery_until=NULL, heartbeat_at=? WHERE id=? AND status IN ('ACTIVE','SUSPECTED')",
            leaseUntil.atOffset(ZoneOffset.UTC), Instant.now().atOffset(ZoneOffset.UTC), attemptId,
        )
    }

    fun finish(attemptId: String, status: String) {
        jdbc.update(
            "UPDATE runtime_attempt SET status=?, completed_at=? WHERE id=? AND status IN ('ACTIVE','SUSPECTED')",
            status, Instant.now().atOffset(ZoneOffset.UTC), attemptId,
        )
    }

    fun markExpiredActive(recoveryUntil: Instant): List<AttemptRecord> {
        val expired = jdbc.query("SELECT * FROM runtime_attempt WHERE status='ACTIVE' AND lease_until < CURRENT_TIMESTAMP", { rs, _ ->
            AttemptRecord(rs.getString("id"), rs.getString("job_id"), rs.getString("worker_id"), rs.getString("worker_boot_id"), rs.getInt("attempt_number"), rs.getString("fencing_token_hash"), rs.getString("status"), rs.getObject("lease_until", OffsetDateTime::class.java).toInstant(), null)
        })
        expired.forEach { jdbc.update("UPDATE runtime_attempt SET status='SUSPECTED', recovery_until=? WHERE id=? AND status='ACTIVE'", recoveryUntil.atOffset(ZoneOffset.UTC), it.id) }
        return expired
    }

    fun abandonExpiredSuspected(): List<AttemptRecord> {
        val expired = jdbc.query("SELECT * FROM runtime_attempt WHERE status='SUSPECTED' AND recovery_until < CURRENT_TIMESTAMP", { rs, _ ->
            AttemptRecord(rs.getString("id"), rs.getString("job_id"), rs.getString("worker_id"), rs.getString("worker_boot_id"), rs.getInt("attempt_number"), rs.getString("fencing_token_hash"), rs.getString("status"), rs.getObject("lease_until", OffsetDateTime::class.java).toInstant(), rs.getObject("recovery_until", OffsetDateTime::class.java)?.toInstant())
        })
        expired.forEach { jdbc.update("UPDATE runtime_attempt SET status='ABANDONED', completed_at=? WHERE id=? AND status='SUSPECTED'", Instant.now().atOffset(ZoneOffset.UTC), it.id) }
        return expired
    }

    fun listWorkers(): List<WorkerView> = jdbc.query("SELECT * FROM runtime_worker ORDER BY worker_id") { rs, _ ->
        val last = rs.getObject("last_heartbeat_at", OffsetDateTime::class.java).toInstant()
        WorkerView(
            rs.getString("worker_id"), rs.getString("boot_id"), if (last.isBefore(Instant.now().minusSeconds(120))) "OFFLINE" else rs.getString("status"),
            readSet(rs.getString("capabilities_json")), readEnumSet(rs.getString("providers_json")), readSet(rs.getString("models_json")), last,
        )
    }

    fun validToken(attempt: AttemptRecord, rawToken: String): Boolean =
        MessageDigest.isEqual(attempt.tokenHash.toByteArray(), hash(rawToken).toByteArray())

    private fun readSet(json: String): Set<String> = mapper.readValue(json, object : TypeReference<Set<String>>() {})
    private fun readEnumSet(json: String): Set<Provider> = mapper.readValue(json, object : TypeReference<Set<Provider>>() {})

    companion object {
        fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    }
}
