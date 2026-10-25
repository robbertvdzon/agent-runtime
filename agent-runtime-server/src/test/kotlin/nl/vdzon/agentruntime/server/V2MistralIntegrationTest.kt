package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(properties = ["agent-runtime.environment=LOCAL", "agent-runtime.object-store-min-free-bytes=0", "agent-runtime.product-factory-providers=CODEX,CLAUDE,MISTRAL,MOCKED"])
@AutoConfigureMockMvc
class V2MistralIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val mapper: ObjectMapper) {
    private val selection = ExecutionSelection("mistral", "glm-5-3", ExecutionMode.SUBSCRIPTION)
    private fun request() = CreateJobRequest(UUID.randomUUID().toString(), JobKind.APPLICATION_WORK, TaskType.STRUCTURED_GENERATION,
        selection, JobInput("Geef JSON."), OutputContract(mapper.readTree("""{"type":"object","required":["title"],"properties":{"title":{"type":"string"}},"additionalProperties":false}""")))

    @Test fun `Mistral is restricted to HKH subscription structured jobs even when another tenant enables its provider`() {
        post("/v2/jobs", "hkh", request(), 202)
        post("/v2/jobs", "product-factory", request(), 422)
        post("/v2/jobs", "hkh-autopilot", request(), 422)
        post("/v2/jobs", "hkh", request().copy(execution=selection.copy(mode=ExecutionMode.API)), 422)
        post("/v2/jobs", "hkh", request().copy(jobKind=JobKind.REPOSITORY_WORK, taskType=TaskType.REPOSITORY_AGENT), 422)
    }

    @Test fun `HKH discovers and completes Mistral jobs with independent server schema validation`() {
        val model = "glm-5-3-${UUID.randomUUID()}"
        val worker = "mistral-${UUID.randomUUID()}"
        val boot = UUID.randomUUID().toString()
        val capability = ExecutorCapability("mistral", model, ExecutionMode.SUBSCRIPTION, setOf(TaskType.STRUCTURED_GENERATION, TaskType.REPOSITORY_AGENT))
        post("/v2/workers/register", "worker", WorkerRegistrationRequest(worker, boot, setOf(capability)), 200)
        fun options(tenant: String, task: TaskType) = mapper.readTree(mvc.perform(get("/v2/execution-options?taskType=$task")
            .header("Authorization", "Bearer local-$tenant-token")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(options("hkh", TaskType.STRUCTURED_GENERATION).any { it.path("execution").path("model").asText() == model }).isTrue()
        assertThat(options("product-factory", TaskType.STRUCTURED_GENERATION).any { it.path("execution").path("vendorId").asText() == "mistral" }).isFalse()
        assertThat(options("hkh", TaskType.REPOSITORY_AGENT).any { it.path("execution").path("vendorId").asText() == "mistral" }).isFalse()
        for (valid in listOf(false, true)) {
            val created = post("/v2/jobs", "hkh", request().copy(execution=selection.copy(model=model)), 202)
            val claim = post("/v2/workers/$worker/claims", "worker", ClaimRequest(boot, setOf(capability), 0), 200)
            assertThat(claim.path("job").path("id").asText()).isEqualTo(created.path("id").asText())
            val body = mapper.readTree(if (valid) """{"title":"klaar"}""" else "{}")
            post("/v2/workers/$worker/jobs/${created.path("id").asText()}/attempts/${claim.path("attempt").path("id").asText()}/result",
                "worker", SubmitResultRequest(claim.path("fencingToken").asText(), body), if (valid) 204 else 422)
        }
    }

    private fun post(path: String, tenant: String, body: Any, expected: Int): JsonNode {
        val response = mvc.perform(post(path).header("Authorization", "Bearer local-$tenant-token").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(body))).andExpect(status().`is`(expected)).andReturn().response
        return if (response.contentAsString.isBlank()) mapper.createObjectNode() else mapper.readTree(response.contentAsString)
    }
}
