package nl.vdzon.agentruntime.contracts.v2

import java.time.LocalDate

/** Calendar dates are inclusive, in [timeZone]. Rows count attempts, including retries. */
data class UsageDashboardResponse(
    val from: LocalDate,
    val through: LocalDate,
    val timeZone: String,
    val projects: List<String>,
    val rows: List<UsageDashboardRow>,
)

data class UsageDashboardRow(
    val project: String,
    val date: LocalDate,
    val vendorId: String,
    val model: String,
    val mode: ExecutionMode,
    val attemptCount: Long,
    val unpricedAttemptCount: Long,
    val partialAttemptCount: Long,
    val costs: List<CostValue>,
    val metrics: List<UsageMetricValue>,
)
