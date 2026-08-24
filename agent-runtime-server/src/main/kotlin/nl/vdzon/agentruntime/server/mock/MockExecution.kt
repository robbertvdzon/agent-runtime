package nl.vdzon.agentruntime.server.mock

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.vdzon.agentruntime.contracts.PrepareMockResponseRequest
import nl.vdzon.agentruntime.contracts.Provider
import nl.vdzon.agentruntime.server.config.*
import nl.vdzon.agentruntime.server.jobs.JobService
import nl.vdzon.agentruntime.server.jobs.JobStore
import nl.vdzon.agentruntime.server.jobs.StoredJob
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class MockResponseView(
    val id: String,
    val tenantId: String?,
    val jobProfile: String?,
    val jobKey: String?,
    val result: JsonNode?,
    val errorCode: String?,
    val errorMessage: String?,
    val delayMillis: Long,
    val createdAt: Instant,
)

@Repository
class MockResponseStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun insert(request: PrepareMockResponseRequest): MockResponseView {
        require((request.result != null) xor (request.errorCode != null)) { "Exactly one of result or errorCode is required." }
        val id = UUID.randomUUID().toString()
        jdbc.update(
            """INSERT INTO runtime_mock_response(id,tenant_id,job_profile,job_key,consumer_correlation,result_json,error_code,error_message,delay_millis,created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            id, request.tenantId, request.jobProfile, request.jobKey, request.consumerCorrelation, request.result?.toString(),
            request.errorCode, request.errorMessage, request.delayMillis, Instant.now().atOffset(ZoneOffset.UTC),
        )
        return list().first { it.id == id }
    }

    fun list(): List<MockResponseView> = jdbc.query(
        "SELECT * FROM runtime_mock_response WHERE consumed_by_job_id IS NULL ORDER BY created_at, id"
    ) { rs, _ ->
        MockResponseView(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("job_profile"), rs.getString("job_key"),
            rs.getString("result_json")?.let(mapper::readTree), rs.getString("error_code"), rs.getString("error_message"),
            rs.getLong("delay_millis"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    fun matching(job: StoredJob): MockResponseView? = list()
        .filter { (it.tenantId == null || it.tenantId == job.view.tenantId) && (it.jobProfile == null || it.jobProfile == job.view.jobProfile) && (it.jobKey == null || it.jobKey == job.view.jobKey) }
        .maxWithOrNull(compareBy<MockResponseView> { specificity(it) }.thenByDescending { -it.createdAt.toEpochMilli() })

    fun consume(id: String, jobId: String): Boolean = jdbc.update(
        "UPDATE runtime_mock_response SET consumed_by_job_id=?, consumed_at=? WHERE id=? AND consumed_by_job_id IS NULL",
        jobId, Instant.now().atOffset(ZoneOffset.UTC), id,
    ) == 1

    fun delete(id: String) = jdbc.update("DELETE FROM runtime_mock_response WHERE id=? AND consumed_by_job_id IS NULL", id)
    fun clear() = jdbc.update("DELETE FROM runtime_mock_response WHERE consumed_by_job_id IS NULL")

    private fun specificity(item: MockResponseView) = listOf(item.tenantId, item.jobProfile, item.jobKey).count { it != null }
}

@Service
class MockExecutor(
    private val properties: RuntimeProperties,
    private val jobs: JobStore,
    private val responses: MockResponseStore,
    private val service: JobService,
) {
    @Scheduled(fixedDelay = 500)
    @Transactional
    fun execute() {
        if (properties.environment == RuntimeEnvironment.PRODUCTION) return
        jobs.queued().firstOrNull { it.view.provider == Provider.MOCKED }?.let(::executeOne)
    }

    private fun executeOne(job: StoredJob) {
        val response = responses.matching(job)
        if (response == null) {
            jobs.fail(job.view.id, "NO_MOCK_RESPONSE_CONFIGURED", "No prepared server-side response matches this mock job.")
            return
        }
        if (!responses.consume(response.id, job.view.id)) return
        if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
        if (response.result != null) {
            runCatching { service.validateResult(job, response.result) }
                .onSuccess { jobs.completeMock(job.view.id, response.result) }
                .onFailure { jobs.fail(job.view.id, "MOCK_RESULT_SCHEMA_INVALID", it.message.orEmpty()) }
        } else jobs.fail(job.view.id, response.errorCode ?: "MOCK_FAILED", response.errorMessage ?: "Prepared mock failure.")
    }
}

@RestController
@RequestMapping("/v1/test-control/mocks")
class MockController(private val properties: RuntimeProperties, private val store: MockResponseStore) {
    @GetMapping fun list(request: HttpServletRequest): List<MockResponseView> { requireAvailable(request); return store.list() }
    @PostMapping fun create(@Valid @RequestBody body: PrepareMockResponseRequest, request: HttpServletRequest): MockResponseView { requireAvailable(request); return store.insert(body) }
    @DeleteMapping("/{id}") fun delete(@PathVariable id: String, request: HttpServletRequest) { requireAvailable(request); store.delete(id) }
    @DeleteMapping fun clear(request: HttpServletRequest) { requireAvailable(request); store.clear() }

    private fun requireAvailable(request: HttpServletRequest) {
        if (properties.environment == RuntimeEnvironment.PRODUCTION) throw ApiException("NOT_FOUND", "Not found.", HttpStatus.NOT_FOUND)
        if (ApiSecurity.identity(request).role != PrincipalRole.ADMIN) throw ApiException("FORBIDDEN", "Administrator required.", HttpStatus.FORBIDDEN)
    }
}
