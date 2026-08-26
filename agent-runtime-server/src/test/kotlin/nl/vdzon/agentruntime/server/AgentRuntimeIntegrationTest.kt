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
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest
import java.util.HexFormat

@SpringBootTest(properties = ["agent-runtime.environment=LOCAL"])
@AutoConfigureMockMvc
class AgentRuntimeIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val mocks: MockResponseStore,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `monitor shell is public while management data remains protected`() {
        mvc.perform(get("/"))
            .andExpect(status().isOk)
        mvc.perform(get("/flutter.js"))
            .andExpect(status().isOk)
        mvc.perform(get("/v1/management/summary"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `incomplete request is a safe client error`() {
        mvc.perform(
            post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("""{"jobKind":"APPLICATION_WORK"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `mocked application work is durable idempotent and schema validated`() {
        val key = UUID.randomUUID().toString()
        mocks.insert(PrepareMockResponseRequest("product-factory", key, result = mapper.readTree("""{"answer":"ok"}""")))
        val request = applicationRequest(key, Provider.MOCKED)
        val first = postJob(PRODUCT_TOKEN, request)
        val second = postJob(PRODUCT_TOKEN, request)
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText())

        val result = awaitResult(first.path("id").asText(), PRODUCT_TOKEN)
        assertThat(result.path("result").path("answer").asText()).isEqualTo("ok")

        val events = getJson("/v1/jobs/${first.path("id").asText()}/events", PRODUCT_TOKEN)
        assertThat(events.map { it.path("type").asText() }).containsExactly("JOB_CREATED", "OUTPUT_ATTEMPT_STARTED", "OUTPUT_ACCEPTED", "MOCK_JOB_SUCCEEDED")
    }

    @Test
    fun `mock output sequence corrects invalid json without a worker`() {
        val key = UUID.randomUUID().toString()
        mocks.insert(PrepareMockResponseRequest("product-factory", key, outputSequence = listOf("not json", """{"answer":"corrected"}""")))
        val job = postJob(PRODUCT_TOKEN, applicationRequest(key, Provider.MOCKED))
        val result = awaitResult(job.path("id").asText(), PRODUCT_TOKEN)
        assertThat(result.path("result").path("answer").asText()).isEqualTo("corrected")
        val events = getJson("/v1/jobs/${job.path("id").asText()}/events", PRODUCT_TOKEN).map { it.path("type").asText() }
        assertThat(events).containsSubsequence("OUTPUT_REJECTED_NOT_JSON", "OUTPUT_ATTEMPT_STARTED", "OUTPUT_ACCEPTED")
    }

    @Test
    fun `worker output protocol validates corrects uploads and finalizes`() {
        val workerId = "output-${UUID.randomUUID()}"
        val bootId = UUID.randomUUID().toString()
        val model = "output-${UUID.randomUUID()}"
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CODEX).copy(model = model))
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(workerId, bootId, setOf("application-work"), setOf(Provider.CODEX), setOf(model)))
        val claim = postJson("/v1/workers/$workerId/claims", WORKER_TOKEN, ClaimRequest(bootId, setOf("application-work"), setOf(Provider.CODEX), setOf(model), 0))
        val id = job.path("id").asText(); val attempt = claim.path("attemptId").asText(); val token = claim.path("fencingToken").asText()
        postJson("/v1/workers/$workerId/jobs/$id/transcript", WORKER_TOKEN, AppendTranscriptRequest(attempt, token, "prompt", 1_000_001, TranscriptKind.PROMPT, "visible prompt"))

        val first = postJson("/v1/workers/$workerId/jobs/$id/output-attempts", WORKER_TOKEN, StartOutputAttemptRequest(attempt, token, "first"))
        val rejected = postJson("/v1/workers/$workerId/jobs/$id/output-candidates", WORKER_TOKEN, SubmitOutputCandidateRequest(attempt, token, first.path("outputAttemptId").asText(), "no json"))
        assertThat(rejected.path("status").asText()).isEqualTo("CORRECTION_REQUIRED")
        val second = postJson("/v1/workers/$workerId/jobs/$id/output-attempts", WORKER_TOKEN, StartOutputAttemptRequest(attempt, token, "second"))
        val accepted = postJson("/v1/workers/$workerId/jobs/$id/output-candidates", WORKER_TOKEN, SubmitOutputCandidateRequest(attempt, token, second.path("outputAttemptId").asText(), """{"answer":"yes"}"""))
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED")

        val bytes = "evidence".toByteArray(); val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        mvc.perform(put("/v1/workers/$workerId/jobs/$id/artifacts").bearer(WORKER_TOKEN)
            .contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Attempt-Id", attempt).header("X-Fencing-Token", token)
            .header("X-Filename", "evidence.txt").header("X-Mime-Type", "text/plain").header("X-Content-SHA256", sha).content(bytes))
            .andExpect(status().isOk)
        postJson("/v1/workers/$workerId/jobs/$id/output-finalization", WORKER_TOKEN, FinalizeAcceptedOutputRequest(attempt, token, second.path("outputAttemptId").asText()))
        val result = getJson("/v1/jobs/$id/result", PRODUCT_TOKEN)
        assertThat(result.path("artifacts")).hasSize(1)
        val completed = getJson("/v1/management/jobs/completed?limit=30", ADMIN_TOKEN).path("items")
            .first { it.path("id").asText() == id }
        assertThat(completed.path("artifactCount").asInt()).isEqualTo(1)
        assertThat(completed.path("inputAttachmentCount").asInt()).isZero()
        assertThat(completed.path("promptPreview").asText()).startsWith("Return a test answer")
        assertThat(completed.path("outputPreview").asText()).contains("yes")
        val transcript = getJson("/v1/management/jobs/$id/transcript", ADMIN_TOKEN)
        assertThat(transcript.path("items")).hasSize(1)
    }

    @Test
    fun `management lists use admin identity and expose no fencing data`() {
        val response = getJson("/v1/management/jobs/completed?limit=30", ADMIN_TOKEN)
        assertThat(response.has("serverTime")).isTrue()
        assertThat(response.toString()).doesNotContain("fencingToken")
        mvc.perform(get("/v1/management/workers").bearer(PRODUCT_TOKEN)).andExpect(status().isForbidden)
    }

    @Test
    fun `consumer isolation and server policy boundaries fail closed`() {
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
        val workerModel = "worker-${UUID.randomUUID()}"
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CODEX).copy(model = workerModel))
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(workerId, bootId, setOf("application-work"), setOf(Provider.CODEX), setOf(workerModel)))
        val claim = postJson("/v1/workers/$workerId/claims", WORKER_TOKEN, ClaimRequest(bootId, setOf("application-work"), setOf(Provider.CODEX), setOf(workerModel), 0))
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
        val workerModel = "fence-${UUID.randomUUID()}"
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CLAUDE).copy(model = workerModel))
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(workerId, bootId, setOf("application-work"), setOf(Provider.CLAUDE), setOf(workerModel)))
        val claim = postJson("/v1/workers/$workerId/claims", WORKER_TOKEN, ClaimRequest(bootId, setOf("application-work"), setOf(Provider.CLAUDE), setOf(workerModel), 0))
        mvc.perform(
            post("/v1/workers/$workerId/jobs/${job.path("id").asText()}/complete").bearer(WORKER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(CompleteAttemptRequest(claim.path("attemptId").asText(), "wrong-token", mapper.readTree("""{"answer":"bad"}"""))))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `removed request fields are rejected`() {
        val body = """{
          "jobKind":"APPLICATION_WORK",
          "idempotencyKey":"${UUID.randomUUID()}",
          "provider":"CODEX",
          "model":"test-model",
          "prompt":"Return JSON",
          "jobKey":"legacy"
        }""".trimIndent()
        mvc.perform(post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `attachments and environment catalog are bounded and values never appear`() {
        val workerId = "catalog-${UUID.randomUUID()}"; val bootId = UUID.randomUUID().toString()
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(
            workerId, bootId, setOf("application-work"), setOf(Provider.CODEX), emptySet(),
            setOf("HKH__SCREENSHOT_USER", "ROBBERTS_ASSISTENT__TEST_USER"), 1,
        ))
        val catalog = getJson("/v1/environment-keys?project=HKH", PRODUCT_TOKEN)
        assertThat(catalog.single().path("name").asText()).isEqualTo("HKH__SCREENSHOT_USER")
        assertThat(catalog.toString()).doesNotContain("password", "secret-value")
        val assistantCatalog = getJson("/v1/environment-keys?project=ROBBERTS_ASSISTENT", PRODUCT_TOKEN)
        assertThat(assistantCatalog.single().path("name").asText()).isEqualTo("ROBBERTS_ASSISTENT__TEST_USER")

        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)
        val valid = applicationRequest(UUID.randomUUID().toString(), Provider.CODEX).copy(
            attachments = listOf(InputAttachmentRequest("screen.png", "image/png", java.util.Base64.getEncoder().encodeToString(png))),
        )
        val created = mapper.readTree(
            mvc.perform(post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(valid)))
                .andExpect(status().isAccepted).andReturn().response.contentAsString
        )
        val jobId = created.path("id").asText()
        val queueItem = getJson("/v1/management/queue", ADMIN_TOKEN).path("items")
            .first { it.path("id").asText() == jobId }
        assertThat(queueItem.path("inputAttachmentCount").asInt()).isEqualTo(1)
        assertThat(queueItem.path("artifactCount").asInt()).isZero()
        assertThat(queueItem.path("promptPreview").asText()).startsWith("Return a test answer")

        val detail = getJson("/v1/management/jobs/$jobId", ADMIN_TOKEN)
        assertThat(detail.path("prompt").asText()).isEqualTo(valid.prompt)
        assertThat(detail.path("inputAttachments")).hasSize(1)
        assertThat(detail.toString()).doesNotContain("contentBase64", java.util.Base64.getEncoder().encodeToString(png))
        val attachmentId = detail.path("inputAttachments").single().path("id").asText()
        val downloaded = mvc.perform(get("/v1/management/jobs/$jobId/attachments/$attachmentId").bearer(ADMIN_TOKEN))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(downloaded).isEqualTo(png)

        val unsafe = valid.copy(idempotencyKey = UUID.randomUUID().toString(), attachments = listOf(InputAttachmentRequest("../screen.png", "image/png", java.util.Base64.getEncoder().encodeToString(png))))
        mvc.perform(post("/v1/jobs").bearer(PRODUCT_TOKEN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(unsafe)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `server fences every operation after hard attempt deadline`() {
        val workerId = "deadline-${UUID.randomUUID()}"; val bootId = UUID.randomUUID().toString(); val model = "deadline-model-${UUID.randomUUID()}"
        val job = postJob(PRODUCT_TOKEN, applicationRequest(UUID.randomUUID().toString(), Provider.CODEX).copy(model = model, executionTimeoutSeconds = 30))
        postJson("/v1/workers/register", WORKER_TOKEN, WorkerRegistrationRequest(workerId, bootId, setOf("application-work"), setOf(Provider.CODEX), setOf(model)))
        val claim = postJson("/v1/workers/$workerId/claims", WORKER_TOKEN, ClaimRequest(bootId, setOf("application-work"), setOf(Provider.CODEX), setOf(model), 0))
        val attempt = claim.path("attemptId").asText(); val token = claim.path("fencingToken").asText(); val id = job.path("id").asText()
        assertThat(Instant.parse(claim.path("attemptDeadline").asText())).isAfter(Instant.now())
        jdbc.update("UPDATE runtime_attempt SET attempt_deadline=DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id=?", attempt)
        mvc.perform(post("/v1/workers/$workerId/jobs/$id/heartbeat").bearer(WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(HeartbeatRequest(attempt, token, bootId))))
            .andExpect(status().isConflict)
        mvc.perform(post("/v1/workers/$workerId/jobs/$id/transcript").bearer(WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(AppendTranscriptRequest(attempt, token, "late", 1, TranscriptKind.AGENT_TEXT, "late"))))
            .andExpect(status().isConflict)
    }

    private fun applicationRequest(key: String, provider: Provider) = CreateJobRequest(
        jobKind = JobKind.APPLICATION_WORK,
        idempotencyKey = key,
        provider = provider,
        model = "test-model",
        prompt = "Return a test answer",
        responseSchema = mapper.readTree("""{"type":"object","required":["answer"],"properties":{"answer":{"type":"string"}}}"""),
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
        const val ADMIN_TOKEN = "local-admin-token"
    }
}
