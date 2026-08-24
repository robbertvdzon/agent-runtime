package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.mock.MockResponseStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["agent-runtime.environment=ACCEPTANCE"])
@AutoConfigureMockMvc
class AgentRuntimeIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val mocks: MockResponseStore,
) {
    @Test
    fun `mocked application work is durable idempotent and schema validated`() {
        val key = UUID.randomUUID().toString()
        mocks.insert(PrepareMockResponseRequest("product-factory", "product-factory-default", key, result = mapper.readTree("""{"answer":"ok"}""")))
        val request = applicationRequest(key, Provider.MOCKED)
        val first = postJob(PRODUCT_TOKEN, request)
        val second = postJob(PRODUCT_TOKEN, request)
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText())

        val result = awaitResult(first.path("id").asText(), PRODUCT_TOKEN)
        assertThat(result.path("result").path("answer").asText()).isEqualTo("ok")

        val events = getJson("/v1/jobs/${first.path("id").asText()}/events", PRODUCT_TOKEN)
        assertThat(events.map { it.path("type").asText() }).containsExactly("JOB_CREATED", "MOCK_JOB_SUCCEEDED")
    }

    @Test
    fun `consumer isolation and profile boundaries fail closed`() {
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CODEX))
        mvc.perform(get("/v1/jobs/${job.path("id").asText()}").bearer(SOFTWARE_TOKEN)).andExpect(status().isNotFound)

        val invalid = applicationRequest(UUID.randomUUID().toString(), Provider.CODEX).copy(jobKind = JobKind.REPOSITORY_WORK)
        mvc.perform(post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(invalid)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `fake worker claims heartbeats reports progress and completes`() {
        val workerId = "fake-${UUID.randomUUID()}"
        val bootId = UUID.randomUUID().toString()
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CODEX).copy(priority = 100))
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(workerId, bootId, setOf("application-work"), setOf(Provider.CODEX), emptySet()))
        val claim = postJson("/v1/workers/$workerId/claims", WORKER_TOKEN, ClaimRequest(bootId, setOf("application-work"), setOf(Provider.CODEX), emptySet(), 0))
        assertThat(claim.path("job").path("id").asText()).isEqualTo(job.path("id").asText())
        val attempt = claim.path("attemptId").asText()
        val token = claim.path("fencingToken").asText()

        val heartbeat = postJson("/v1/workers/$workerId/jobs/${job.path("id").asText()}/heartbeat", WORKER_TOKEN, HeartbeatRequest(attempt, token, bootId))
        assertThat(heartbeat.path("accepted").asBoolean()).isTrue()
        postJson("/v1/workers/$workerId/jobs/${job.path("id").asText()}/progress", WORKER_TOKEN, ProgressRequest(attempt, token, "EXECUTING", 50, "Working safely"))
        postJson("/v1/workers/$workerId/jobs/${job.path("id").asText()}/complete", WORKER_TOKEN, CompleteAttemptRequest(attempt, token, mapper.readTree("""{"answer":"worker"}""")))

        val result = getJson("/v1/jobs/${job.path("id").asText()}/result", PRODUCT_TOKEN)
        assertThat(result.path("result").path("answer").asText()).isEqualTo("worker")
    }

    @Test
    fun `invalid fencing token cannot change a running job`() {
        val workerId = "fence-${UUID.randomUUID()}"
        val bootId = UUID.randomUUID().toString()
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CLAUDE))
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(workerId, bootId, setOf("application-work"), setOf(Provider.CLAUDE), emptySet()))
        val claim = postJson("/v1/workers/$workerId/claims", WORKER_TOKEN, ClaimRequest(bootId, setOf("application-work"), setOf(Provider.CLAUDE), emptySet(), 0))
        mvc.perform(
            post("/v1/workers/$workerId/jobs/${job.path("id").asText()}/complete").bearer(WORKER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(CompleteAttemptRequest(claim.path("attemptId").asText(), "wrong-token", mapper.readTree("""{"answer":"bad"}"""))))
        ).andExpect(status().isConflict)
    }

    private fun applicationRequest(key: String, provider: Provider) = CreateJobRequest(
        JobKind.APPLICATION_WORK, key, "product-factory-default", key, provider, "test-model", "1", "1",
        "Return a test answer", mapper.createObjectNode(), mapper.readTree("""{"type":"object","required":["answer"],"properties":{"answer":{"type":"string"}}}"""),
    )

    private fun postJob(token: String, body: Any) = postJson("/v1/jobs", token, body)

    private fun postJson(path: String, token: String, body: Any): JsonNode {
        val response = mvc.perform(post(path).bearer(token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
            .andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        return if (response.isBlank()) mapper.createObjectNode() else mapper.readTree(response)
    }

    private fun getJson(path: String, token: String): JsonNode = mapper.readTree(
        mvc.perform(get(path).bearer(token)).andExpect(status().isOk).andReturn().response.contentAsString
    )

    private fun awaitResult(id: String, token: String): JsonNode {
        val deadline = Instant.now().plus(Duration.ofSeconds(5))
        while (Instant.now().isBefore(deadline)) {
            val response = mvc.perform(get("/v1/jobs/$id/result").bearer(token)).andReturn().response
            if (response.status == 200) return mapper.readTree(response.contentAsString)
            Thread.sleep(50)
        }
        error("Timed out waiting for mock result")
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token: String) =
        header("Authorization", "Bearer $token")

    companion object {
        const val PRODUCT_TOKEN = "local-product-factory-token"
        const val SOFTWARE_TOKEN = "local-software-factory-token"
        const val WORKER_TOKEN = "local-worker-token"
    }
}
