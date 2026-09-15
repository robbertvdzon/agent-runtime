package nl.vdzon.agentruntime.server.v2

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@RestController
class V2UsageDashboardController(private val dashboard: V2UsageDashboardService) {
    @GetMapping("/v2/management/usage/dashboard")
    fun get(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) through: LocalDate?,
        @RequestParam(defaultValue = "Europe/Amsterdam") timeZone: String,
        request: HttpServletRequest,
    ): UsageDashboardResponse {
        request.requireAdmin()
        return dashboard.load(from, through, timeZone)
    }
}

@Service
class V2UsageDashboardService(private val jdbc: JdbcTemplate, private val properties: RuntimeProperties) {
    private data class Key(val project: String, val date: LocalDate, val vendor: String, val model: String, val mode: ExecutionMode)
    private data class Attempt(val id: String, val key: Key, val quality: UsageQuality)
    private data class Metric(val attempt: String, val id: Long, val metric: UsageMetric, val quantity: BigDecimal, val unit: UsageUnit)
    private data class Cost(val attempt: String, val usage: Long?, val kind: CostKind, val status: CostStatus, val amount: BigDecimal, val currency: String)

    // Separate reads avoid multiplying costs by the number of usage metrics. The read-only
    // snapshot keeps the three reads consistent while workers append new usage concurrently.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun load(from: LocalDate?, through: LocalDate?, timeZone: String): UsageDashboardResponse {
        val zone = try { ZoneId.of(timeZone) } catch (_: DateTimeException) {
            throw ApiException("INVALID_TIME_ZONE", "Use an IANA time zone, for example Europe/Amsterdam.")
        }
        val end = through ?: LocalDate.now(zone)
        val start = from ?: end.minusDays(29)
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 366) {
            throw ApiException("INVALID_PERIOD", "Choose an inclusive period of 1 to 366 calendar days.")
        }
        val args = arrayOf(V2JobStore.utc(start.atStartOfDay(zone).toInstant()), V2JobStore.utc(end.plusDays(1).atStartOfDay(zone).toInstant()))
        val where = "a.started_at >= ? AND a.started_at < ? AND a.execution_mode IN ('API', 'SUBSCRIPTION')"
        val attempts = jdbc.query(
            """SELECT a.id, j.tenant_id, a.started_at, a.vendor_id, a.model, a.execution_mode, a.usage_quality
                FROM runtime_v2_attempt a JOIN runtime_v2_job j ON j.id = a.job_id WHERE $where""",
            { rs, _ -> Attempt(rs.getString("id"), Key(rs.getString("tenant_id"), V2UsageStore.instant(rs.getObject("started_at")).atZone(zone).toLocalDate(),
                rs.getString("vendor_id"), rs.getString("model"), ExecutionMode.valueOf(rs.getString("execution_mode"))), UsageQuality.valueOf(rs.getString("usage_quality"))) },
            *args,
        )
        val metrics = jdbc.query(
            """SELECT u.* FROM runtime_v2_usage u JOIN runtime_v2_attempt a ON a.id = u.attempt_id WHERE $where""",
            { rs, _ -> Metric(rs.getString("attempt_id"), rs.getLong("id"), UsageMetric.valueOf(rs.getString("metric")), rs.getBigDecimal("quantity"), UsageUnit.valueOf(rs.getString("unit"))) },
            *args,
        ).groupBy { it.attempt }
        val costs = jdbc.query(
            """SELECT c.* FROM runtime_v2_cost c JOIN runtime_v2_attempt a ON a.id = c.attempt_id WHERE $where
                AND ((a.execution_mode = 'API' AND c.cost_kind IN ('DIRECT', 'CALCULATED'))
                    OR (a.execution_mode = 'SUBSCRIPTION' AND c.cost_kind = 'API_EQUIVALENT'))""",
            { rs, _ -> Cost(rs.getString("attempt_id"), (rs.getObject("usage_id") as? Number)?.toLong(), CostKind.valueOf(rs.getString("cost_kind")),
                CostStatus.valueOf(rs.getString("cost_status")), rs.getBigDecimal("amount"), rs.getString("currency")) },
            *args,
        ).groupBy { it.attempt }.mapValues { (_, values) ->
            // A provider's final charge supersedes its calculated estimate, not adds to it.
            values.filter { cost -> cost.kind != CostKind.CALCULATED || values.none {
                it.kind == CostKind.DIRECT && it.currency == cost.currency && (it.usage == null || it.usage == cost.usage)
            } }
        }
        val historical = jdbc.query("SELECT DISTINCT tenant_id FROM runtime_v2_job", { rs, _ -> rs.getString(1) })
        val projects = (properties.consumerTokens().keys + historical).distinct().sorted()
        val rows = attempts.groupBy { it.key }.map { (key, group) ->
            val ownCosts = group.flatMap { costs[it.id].orEmpty() }
            val ownMetrics = group.flatMap { metrics[it.id].orEmpty() }
            val unpriced = group.count { costs[it.id].isNullOrEmpty() }
            val partial = group.count { attempt ->
                val priced = costs[attempt.id].orEmpty()
                priced.isNotEmpty() && (attempt.quality != UsageQuality.COMPLETE ||
                    (priced.none { it.kind == CostKind.DIRECT && it.usage == null } && metrics[attempt.id].orEmpty().any { metric ->
                        metric.quantity.signum() > 0 && priced.none { it.usage == metric.id }
                    }))
            }
            UsageDashboardRow(key.project, key.date, key.vendor, key.model, key.mode, group.size.toLong(), unpriced.toLong(), partial.toLong(),
                ownCosts.groupBy { Triple(it.kind, it.status, it.currency) }.map { (kind, values) ->
                    CostValue(kind.first, kind.second, values.sumOf { it.amount }.stripTrailingZeros().toPlainString(), kind.third)
                },
                ownMetrics.groupBy { it.metric to it.unit }.map { (metric, values) ->
                    UsageMetricValue(metric.first, values.sumOf { it.quantity }.stripTrailingZeros().toPlainString(), metric.second)
                },
            )
        }.sortedWith(compareBy({ it.project }, { it.date }, { it.vendorId }, { it.model }, { it.mode }))
        return UsageDashboardResponse(start, end, zone.id, projects, rows)
    }
}
