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
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["agent-runtime.environment=LOCAL", "agent-runtime.object-store-min-free-bytes=0", "agent-runtime.test-control-token=local-test-control-token"])
@AutoConfigureMockMvc
class V2ProviderLimitsIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val mapper: ObjectMapper) {
    private data class Fixture(val worker: String, val boot: String, val capabilities: Set<ExecutorCapability>, val jobs: List<String>)

    private fun fixture(vendors: List<String>, limit: Int? = null): Fixture {
        val model = "limit-test-${UUID.randomUUID()}"
        val worker = "limit-worker-${UUID.randomUUID()}"
        val boot = UUID.randomUUID().toString()
        val caps = vendors.toSet().map { ExecutorCapability(it,model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION)) }.toSet()
        val registered=postJson("/v2/workers/register","worker",WorkerRegistrationRequest(worker,boot,caps,maxConcurrency=50,
            providerConcurrencyLimits=limit?.let { mapOf(vendors.first() to it) }.orEmpty()),200)
        if (limit==null) assertThat(registered.has("providerConcurrencyLimits")).isFalse()
        val jobs=vendors.map { vendor -> postJson("/v2/jobs","hkh",CreateJobRequest(UUID.randomUUID().toString(),JobKind.APPLICATION_WORK,
            TaskType.STRUCTURED_GENERATION,ExecutionSelection(vendor,model,ExecutionMode.SUBSCRIPTION),JobInput("Synthetic limit test"),
            OutputContract(mapper.readTree("""{"type":"object"}"""))),202).path("id").asText() }
        return Fixture(worker,boot,caps,jobs)
    }

    private fun claim(f: Fixture, expected: Int = 200) = postJson("/v2/workers/${f.worker}/claims","worker",ClaimRequest(f.boot,f.capabilities,0),expected)
    private fun base(f: Fixture, claim: JsonNode) = "/v2/workers/${f.worker}/jobs/${claim.path("job").path("id").asText()}/attempts/${claim.path("attempt").path("id").asText()}"

    @Test fun `live limit is tenant scoped idempotent and survives retries with failed attempt usage`() {
        val f=fixture(listOf("anthropic")); val c=claim(f); val job=f.jobs.single(); val base=base(f,c)
        val fence=c.path("fencingToken").asText(); val reset=Instant.now().plusSeconds(600)
        val observation=ProviderLimitObservation("same-event",ProviderLimitKind.SHORT_WINDOW,Instant.now(),resetsAt=reset,
            retryAfterSeconds=900,providerCode="five_hour",source=ProviderLimitSource.PROVIDER_STRUCTURED)
        repeat(2) { postJson("$base/provider-limits","worker",AppendProviderLimitRequest(fence,observation),204) }
        val events=getJson("/v2/jobs/$job/events","hkh").path("items").filter { it.path("type").asText()=="PROVIDER_LIMIT_OBSERVED" }
        assertThat(events).hasSize(1)
        assertThat(events.single().path("attemptId").asText()).isEqualTo(c.path("attempt").path("id").asText())
        assertThat(events.single().path("providerLimit").path("kind").asText()).isEqualTo("SHORT_WINDOW")
        getJson("/v2/jobs/$job/events","hkh-autopilot",404)
        postJson("$base/provider-limits","worker",AppendProviderLimitRequest("wrong",observation.copy(eventId="bad")),409)
        postJson("$base/provider-limits","worker",AppendProviderLimitRequest(fence,observation.copy(eventId="future",observedAt=Instant.now().plusSeconds(120))),400)
        postJson("$base/fail","worker",FailAttemptRequest(fence,"PROVIDER_RATE_LIMIT","Limited",true,listOf(observation)),204)
        val view=getJson("/v2/jobs/$job","hkh")
        assertThat(view.path("status").asText()).isEqualTo("QUEUED")
        // PostgreSQL/H2 timestamps store microseconds; Linux Instant.now() can include nanoseconds.
        assertThat(Instant.parse(view.path("retryNotBefore").asText()))
            .isAfterOrEqualTo(observation.observedAt.plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        claim(f,204)
        // Late evidence is still append-only; it cannot submit a result for the old attempt.
        postJson("$base/provider-limits","worker",AppendProviderLimitRequest(fence,observation),204)
        postJson("$base/result","worker",SubmitResultRequest(fence,mapper.createObjectNode()),409)
        // Accounting remains legal after failure; consumers need no successful result.
        postJson("$base/usage-events","worker",AppendUsageRequest(fence,"usage",observation.observedAt,
            listOf(UsageMetricValue(UsageMetric.CACHED_INPUT_TOKENS,"123",UsageUnit.TOKEN)),source=UsageSource.PROVIDER_REPORTED,complete=false),204)
        val usage=getJson("/v2/jobs/$job/usage","hkh")
        assertThat(usage.path("usageQuality").asText()).isEqualTo("PARTIAL")
        assertThat(usage.path("metrics").single().path("quantity").asText()).isEqualTo("123")
        getJson("/v2/jobs/$job/usage","hkh-autopilot",404)
        assertThat(getJson("/v2/jobs/$job/events","hkh").path("items").count { it.path("type").asText()=="PROVIDER_LIMIT_OBSERVED" }).isEqualTo(1)
    }

    @Test fun `long quota delivered with failure is preserved and is not automatically retried`() {
        for (kind in listOf(ProviderLimitKind.WEEK,ProviderLimitKind.MONTH)) {
            val f=fixture(listOf("mistral"));val c=claim(f)
            val observation=ProviderLimitObservation(UUID.randomUUID().toString(),kind,Instant.now(),source=ProviderLimitSource.PROVIDER_ERROR)
            postJson("${base(f,c)}/fail","worker",FailAttemptRequest(c.path("fencingToken").asText(),"PROVIDER_QUOTA_EXCEEDED","Quota",true,listOf(observation)),204)
            assertThat(getJson("/v2/jobs/${f.jobs.single()}","hkh").path("status").asText()).isEqualTo("FAILED")
            assertThat(getJson("/v2/jobs/${f.jobs.single()}/events","hkh").path("items").any { it.path("providerLimit").path("kind").asText()==kind.name }).isTrue()
        }
    }

    @Test fun `provider slot cap leaves another provider executable and supports fifty total slots`() {
        val f=fixture(listOf("anthropic","anthropic","mistral"),limit=1)
        val first=claim(f); assertThat(first.path("job").path("execution").path("vendorId").asText()).isEqualTo("anthropic")
        assertThat(first.path("job").has("retryNotBefore")).isFalse()
        val second=claim(f); assertThat(second.path("job").path("execution").path("vendorId").asText()).isEqualTo("mistral")
        claim(f,204)
        postJson("${base(f,first)}/fail","worker",FailAttemptRequest(first.path("fencingToken").asText(),"TEST","Done",false),204)
        assertThat(claim(f).path("job").path("id").asText()).isEqualTo(f.jobs[1])
    }

    @Test fun `fifty simultaneous claims are unique and a fifty first job stays queued`() {
        val f=fixture(List(51) { "anthropic" })
        val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures=(1..50).map { pool.submit<String> { claim(f).path("job").path("id").asText() } }
            val ids=futures.map { it.get(30,java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(ids.toSet()).hasSize(50)
            assertThat(ids).containsAll(f.jobs.take(50))
            claim(f,204)
            assertThat(getJson("/v2/jobs/${f.jobs.last()}","hkh").path("attemptCount").asInt()).isZero()
        } finally { pool.shutdownNow() }
    }

    @Test fun `HKH can exercise structured quota events using only test control and mock execution`() {
        val key=UUID.randomUUID().toString()
        val limit=ProviderLimitObservation("mock-limit",ProviderLimitKind.MONTH,Instant.EPOCH,source=ProviderLimitSource.PROVIDER_ERROR)
        val fixture=CreateMockFixtureRequest("hkh",key,errorCode="PROVIDER_QUOTA_EXCEEDED",providerLimits=listOf(limit))
        postJson("/v2/test-control/mocks","test-control",fixture,200)
        val job=postJson("/v2/jobs","hkh",CreateJobRequest(key,JobKind.APPLICATION_WORK,TaskType.STRUCTURED_GENERATION,
            ExecutionSelection("mock","mock",ExecutionMode.MOCK),JobInput("Synthetic quota"),OutputContract(mapper.readTree("""{"type":"object"}"""))),202).path("id").asText()
        val deadline=System.nanoTime()+10_000_000_000
        while(getJson("/v2/jobs/$job","hkh").path("status").asText()!="FAILED" && System.nanoTime()<deadline) Thread.sleep(25)
        val event=getJson("/v2/jobs/$job/events","hkh").path("items").single { it.path("type").asText()=="PROVIDER_LIMIT_OBSERVED" }
        assertThat(event.path("providerLimit").path("kind").asText()).isEqualTo("MONTH")
        assertThat(Instant.parse(event.path("providerLimit").path("observedAt").asText())).isAfter(Instant.EPOCH)
        assertThat(event.path("attemptId").asText()).isNotBlank()
    }

    private fun getJson(path: String, tenant: String, expected: Int=200): JsonNode = mvc.perform(get(path).header("Authorization","Bearer local-$tenant-token"))
        .andExpect(status().`is`(expected)).andReturn().response.contentAsString.let { mapper.readTree(it) }
    private fun postJson(path: String, tenant: String, body: Any, expected: Int): JsonNode = mvc.perform(post(path)
        .header("Authorization","Bearer local-$tenant-token").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
        .andExpect(status().`is`(expected)).andReturn().response.contentAsString.let { if(it.isBlank())mapper.createObjectNode() else mapper.readTree(it) }
}
