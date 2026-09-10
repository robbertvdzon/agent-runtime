package nl.vdzon.agentruntime.server.monitor

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

data class ManagementJobFact(
    val consumer: String,
    val apiVersion: String,
    val vendorId: String,
    val model: String,
    val mode: String?,
    val createdAt: Instant,
)

@Repository
class ManagementAnalyticsStore(private val jdbc: JdbcTemplate) {
    fun jobFacts(): List<ManagementJobFact> =
        jdbc.query("SELECT tenant_id,provider,model,created_at FROM runtime_job") { rs, _ ->
            ManagementJobFact(
                rs.getString("tenant_id"), "v1", rs.getString("provider"), rs.getString("model"), null,
                instant(rs.getObject("created_at")),
            )
        } + jdbc.query("SELECT tenant_id,vendor_id,model,execution_mode,created_at FROM runtime_v2_job") { rs, _ ->
            ManagementJobFact(
                rs.getString("tenant_id"), "v2", rs.getString("vendor_id"), rs.getString("model"),
                rs.getString("execution_mode"), instant(rs.getObject("created_at")),
            )
        }

    private fun instant(value: Any): Instant = when (value) {
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> error("Unsupported timestamp value: ${value::class.simpleName}")
    }
}
