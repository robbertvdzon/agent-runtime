package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.CreateJobRequest
import nl.vdzon.agentruntime.contracts.JobKind
import nl.vdzon.agentruntime.contracts.Provider
import nl.vdzon.agentruntime.contracts.WorkerRegistrationRequest
import nl.vdzon.agentruntime.server.config.RuntimeEnvironment
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(properties = [
    "agent-runtime.environment=ACCEPTANCE",
    "agent-runtime.worker-api-enabled=false",
    "agent-runtime.product-factory-providers=MOCKED",
    "agent-runtime.software-factory-providers=MOCKED",
    "agent-runtime.hkh-autopilot-providers=MOCKED",
    "agent-runtime.hkh-providers=MOCKED",
    "spring.datasource.url=jdbc:h2:mem:agent_runtime_acceptance_policy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
])
@AutoConfigureMockMvc
class AcceptancePolicyIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
) {
    @Test
    fun `acceptance only accepts mocked jobs`() {
        val mocked = request(Provider.MOCKED)
        mvc.perform(post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mocked)))
            .andExpect(status().isAccepted)
        mvc.perform(
            post("/v1/jobs").bearer(HKH_AUTOPILOT_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request(Provider.MOCKED)))
        ).andExpect(status().isAccepted)
        mvc.perform(
            post("/v1/jobs").bearer(HKH_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request(Provider.MOCKED)))
        ).andExpect(status().isAccepted)

        listOf(Provider.CODEX, Provider.CLAUDE).forEach { provider ->
            val response = mvc.perform(
                post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(request(provider)))
            ).andExpect(status().isBadRequest).andReturn().response
            assertThat(mapper.readTree(response.contentAsString).path("code").asText())
                .isEqualTo("PROVIDER_FORBIDDEN_IN_ACCEPTANCE")
        }
    }

    @Test
    fun `acceptance does not expose the worker api`() {
        val registration = WorkerRegistrationRequest(
            workerId = "acceptance-worker",
            bootId = UUID.randomUUID().toString(),
            capabilities = setOf("application-work"),
            providers = setOf(Provider.CODEX),
            models = setOf("test-model"),
        )
        val response = mvc.perform(
            post("/v1/workers/register").bearer(WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(registration))
        ).andExpect(status().isNotFound).andReturn().response
        assertThat(mapper.readTree(response.contentAsString).path("code").asText()).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `acceptance configuration cannot widen the execution boundary`() {
        assertThatThrownBy {
            RuntimeProperties(
                environment = RuntimeEnvironment.ACCEPTANCE,
                workerApiEnabled = true,
                productFactoryProviders = "MOCKED",
                softwareFactoryProviders = "MOCKED",
                hkhAutopilotProviders = "MOCKED",
                hkhProviders = "MOCKED",
            ).validate()
        }.hasMessage("Acceptance must have the worker API disabled.")

        assertThatThrownBy {
            RuntimeProperties(
                environment = RuntimeEnvironment.ACCEPTANCE,
                workerApiEnabled = false,
                productFactoryProviders = "MOCKED,CODEX",
                softwareFactoryProviders = "MOCKED",
                hkhAutopilotProviders = "MOCKED",
                hkhProviders = "MOCKED",
            ).validate()
        }.hasMessage("Acceptance Product Factory may only allow MOCKED.")

        assertThatThrownBy {
            RuntimeProperties(
                environment = RuntimeEnvironment.ACCEPTANCE,
                workerApiEnabled = false,
                productFactoryProviders = "MOCKED",
                softwareFactoryProviders = "MOCKED",
                hkhAutopilotProviders = "MOCKED,CODEX",
                hkhProviders = "MOCKED",
            ).validate()
        }.hasMessage("Acceptance HKH Autopilot may only allow MOCKED.")
    }

    private fun request(provider: Provider) = CreateJobRequest(
        jobKind = JobKind.APPLICATION_WORK,
        idempotencyKey = UUID.randomUUID().toString(),
        provider = provider,
        model = "test-model",
        prompt = "Return a test answer",
    )

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token: String) =
        header("Authorization", "Bearer $token")

    companion object {
        const val PRODUCT_TOKEN = "local-product-factory-token"
        const val HKH_AUTOPILOT_TOKEN = "local-hkh-autopilot-token"
        const val HKH_TOKEN = "local-hkh-token"
        const val WORKER_TOKEN = "local-worker-token"
    }
}
