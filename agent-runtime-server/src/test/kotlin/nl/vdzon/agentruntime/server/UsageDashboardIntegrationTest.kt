package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest(properties = ["agent-runtime.environment=LOCAL", "spring.datasource.url=jdbc:h2:mem:usage-dashboard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"])
@AutoConfigureMockMvc
@Transactional
class UsageDashboardIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mapper: ObjectMapper,
) {
    @Test
    fun `calendar range respects Amsterdam DST and includes failed retries exactly once`() {
        val first = attempt("dashboard-a", "2026-03-28T23:00:00Z", "API")
        val second = attempt("dashboard-a", "2026-03-29T21:59:59Z", "API", job = first.first, number = 2)
        val excluded = attempt("dashboard-a", "2026-03-29T22:00:00Z", "API")
        cost(first, metric(first, "INPUT_TOKENS", "100"), "CALCULATED", "1.25")
        cost(first, metric(first, "OUTPUT_TOKENS", "40"), "CALCULATED", "2.50")
        cost(second, metric(second, "INPUT_TOKENS", "20"), "CALCULATED", "0.25")
        cost(excluded, null, "DIRECT", "100")
        jdbc.update("UPDATE runtime_v2_attempt SET status='FAILED' WHERE id=?", first.second)
        val dashboard = read()
        val rows = dashboard.path("rows").filter { it.path("project").asText() == "dashboard-a" }
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.path("date").asText()).isEqualTo("2026-03-29")
        assertThat(row.path("attemptCount").asInt()).isEqualTo(2)
        assertThat(row.path("costs").sumOf { BigDecimal(it.path("amount").asText()) }).isEqualByComparingTo("4")
        assertThat(row.path("metrics").first { it.path("metric").asText() == "INPUT_TOKENS" }.path("quantity").asText()).isEqualTo("120")
        assertThat(row.path("unpricedAttemptCount").asInt()).isZero()
    }

    @Test
    fun `dashboard keeps cost kinds currencies missing prices and zero usage distinct`() {
        val subscription = attempt("dashboard-sub", "2026-03-29T10:00:00Z", "SUBSCRIPTION")
        cost(subscription, metric(subscription, "INPUT_TOKENS", "300"), "API_EQUIVALENT", "3")
        metric(subscription, "OUTPUT_TOKENS", "50") // unpriced part of a measured attempt
        cost(subscription, null, "ALLOCATED", "100") // must not be added to API-equivalent value
        val api = attempt("dashboard-api", "2026-03-29T10:00:00Z", "API")
        cost(api, metric(api, "INPUT_TOKENS", "100"), "CALCULATED", "7")
        cost(api, null, "DIRECT", "5") // replaces the calculation; no usage_id required
        cost(api, null, "DIRECT", "2", "EUR")
        attempt("dashboard-unknown", "2026-03-29T10:00:00Z", "API", quality = "UNAVAILABLE")
        attempt("dashboard-local", "2026-03-29T10:00:00Z", "LOCAL")
        attempt("dashboard-mock", "2026-03-29T10:00:00Z", "MOCK")
        val dashboard = read()
        val rows = dashboard.path("rows")
        assertThat(rows.none { it.path("mode").asText() in setOf("LOCAL", "MOCK") }).isTrue()
        val sub = rows.single { it.path("project").asText() == "dashboard-sub" }
        assertThat(sub.path("partialAttemptCount").asInt()).isEqualTo(1)
        assertThat(sub.path("costs").map { it.path("kind").asText() }).containsExactly("API_EQUIVALENT")
        val direct = rows.single { it.path("project").asText() == "dashboard-api" }
        assertThat(direct.path("costs").map { it.path("amount").asText() }).containsExactlyInAnyOrder("5", "2")
        assertThat(direct.path("costs").map { it.path("currency").asText() }).containsExactlyInAnyOrder("USD", "EUR")
        val unknown = rows.single { it.path("project").asText() == "dashboard-unknown" }
        assertThat(unknown.path("unpricedAttemptCount").asInt()).isEqualTo(1)
        assertThat(unknown.path("costs")).isEmpty()
        assertThat(dashboard.path("projects").map { it.asText() }).contains("hkh-autopilot", "dashboard-local")
    }

    @Test
    fun `empty periods keep configured projects and admin access is required`() {
        val result = read("2020-01-01", "2020-01-01")
        assertThat(result.path("rows")).isEmpty()
        assertThat(result.path("projects").map { it.asText() }).contains("product-factory", "hkh-autopilot")
        mvc.perform(get("/v2/management/usage/dashboard").header("Authorization", "Bearer local-product-factory-token"))
            .andExpect(status().isForbidden)
        mvc.perform(get("/v2/management/usage/dashboard")).andExpect(status().isUnauthorized)
        mvc.perform(get("/v2/management/usage/dashboard").header("Authorization", "Bearer local-admin-token")
            .param("from", "2026-09-15").param("through", "2026-09-14")).andExpect(status().isBadRequest)
        mvc.perform(get("/v2/management/usage/dashboard").header("Authorization", "Bearer local-admin-token")
            .param("timeZone", "invalid-zone")).andExpect(status().isBadRequest)
        mvc.perform(get("/v2/management/usage/dashboard").header("Authorization", "Bearer local-admin-token")
            .param("from", "2024-01-01").param("through", "2026-09-14")).andExpect(status().isBadRequest)
    }

    private fun read(from: String = "2026-03-29", through: String = "2026-03-29") = mapper.readTree(
        mvc.perform(get("/v2/management/usage/dashboard").header("Authorization", "Bearer local-admin-token")
            .param("from", from).param("through", through).param("timeZone", "Europe/Amsterdam"))
            .andExpect(status().isOk)
            .andExpect { assertThat(it.response.getHeader("Cache-Control")).contains("no-store") }
            .andReturn().response.contentAsString,
    )

    private fun attempt(project: String, started: String, mode: String, job: String? = null, number: Int = 1, quality: String = "COMPLETE"): Pair<String, String> {
        val jobId = job ?: UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val at = OffsetDateTime.parse(started)
        if (job == null) jdbc.update("""INSERT INTO runtime_v2_job
            (id,tenant_id,idempotency_key,job_kind,task_type,vendor_id,model,execution_mode,request_json,status,phase,max_attempts,created_at,updated_at)
            VALUES (?,?,?,'APPLICATION_WORK','STRUCTURED_GENERATION','openai','dashboard-model',?,'{}','SUCCEEDED','DONE',3,?,?)""", jobId, project, jobId, mode, at, at)
        jdbc.update("""INSERT INTO runtime_v2_attempt
            (id,job_id,worker_id,worker_boot_id,attempt_number,reason,status,vendor_id,model,execution_mode,task_type,fencing_token_hash,lease_until,attempt_deadline,heartbeat_at,usage_quality,started_at)
            VALUES (?,?,'dashboard-test','test',?,'INITIAL','SUCCEEDED','openai','dashboard-model',?,'STRUCTURED_GENERATION','test',?,?,?,?,?)""",
            id, jobId, number, mode, at, at, at, quality, at)
        return jobId to id
    }
    private fun metric(attempt: Pair<String, String>, name: String, quantity: String): Long {
        jdbc.update("""INSERT INTO runtime_v2_usage (job_id,attempt_id,external_event_id,metric,quantity,unit,source,observed_at,created_at)
            VALUES (?,?,?,?,?,'TOKEN','PROVIDER_REPORTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)""", attempt.first, attempt.second, name, name, BigDecimal(quantity))
        return jdbc.queryForObject("SELECT id FROM runtime_v2_usage WHERE attempt_id=? AND metric=?", Long::class.java, attempt.second, name)!!
    }
    private fun cost(attempt: Pair<String, String>, usage: Long?, kind: String, amount: String, currency: String = "USD") {
        jdbc.update("""INSERT INTO runtime_v2_cost (job_id,attempt_id,usage_id,cost_kind,cost_status,amount,currency,created_at)
            VALUES (?,?,?,?,'ESTIMATED',?,?,CURRENT_TIMESTAMP)""", attempt.first, attempt.second, usage, kind, BigDecimal(amount), currency)
    }
}
