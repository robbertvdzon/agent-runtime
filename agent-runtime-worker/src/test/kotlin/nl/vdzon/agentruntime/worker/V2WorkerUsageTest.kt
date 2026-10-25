package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import nl.vdzon.agentruntime.contracts.v2.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class V2WorkerUsageTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test fun `provider failure retains reported tokens before the failed attempt is cleaned up`(@TempDir root: Path) {
        exercise(root, "ENGINE_FAILED", 1, null)
    }

    @Test fun `invalid JSON does not discard successful provider usage`(@TempDir root: Path) {
        exercise(root, "MODEL_OUTPUT_NOT_JSON", 0, "not JSON")
    }

    @Test fun `missing required artifact does not discard successful provider usage`(@TempDir root: Path) {
        exercise(root, "MISSING_REQUIRED_ARTIFACT", 0, "{}", requiredArtifact = true)
    }

    @Test fun `Mistral quota failure preserves usage and keeps subscription credentials out of arguments`(@TempDir root: Path) {
        exercise(root, "PROVIDER_UNAVAILABLE", 1, null, mistral = true, failureCode = "PROVIDER_UNAVAILABLE")
    }

    @Test fun `Mistral invalid final schema is rejected even after CLI success`(@TempDir root: Path) {
        exercise(root, "MODEL_OUTPUT_SCHEMA_INVALID", 1, null, mistral = true, failureCode = "MODEL_OUTPUT_SCHEMA_INVALID")
    }

    @Test fun `Mistral limit reaches the server before failure and is repeated with stable ID in failure fallback`(@TempDir root: Path) {
        exercise(root, "PROVIDER_QUOTA_EXCEEDED", 1, null, mistral=true, failureCode="PROVIDER_UNAVAILABLE",
            limitEvent="""{"type":"mistral.limit","kind":"MONTH","http_status":429,"provider_code":"monthly_quota_exceeded"}""")
    }

    @Test fun `Ollama uses Codex without cloud credentials and preserves usage on invalid output`(@TempDir root: Path) {
        exercise(root, "MODEL_OUTPUT_NOT_JSON", 0, "not JSON", ollama = true)
    }

    @Test fun `Ollama repair stage is forwarded to progress without blocking usage or heartbeats`(@TempDir root: Path) {
        exercise(root, "MODEL_OUTPUT_NOT_JSON", 0, "not JSON", ollama = true,
            progressEvent = """{"type":"ar.ollama.phase","stage":"repair","attempt":1}""")
    }

    @Test fun `other providers cannot change progress through Ollama events`(@TempDir root: Path) {
        exercise(root, "MODEL_OUTPUT_NOT_JSON", 0, "not JSON",
            progressEvent = """{"type":"ar.ollama.phase","stage":"repair","attempt":1}""")
    }

    private fun exercise(root: Path, expectedError: String, exit: Int, output: String?, requiredArtifact: Boolean = false, mistral: Boolean = false, failureCode: String? = null, limitEvent: String? = null, ollama: Boolean = false, progressEvent: String? = null) {
        val calls = CopyOnWriteArrayList<Pair<String, JsonNode>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += exchange.requestURI.path to mapper.readTree(exchange.requestBody)
            if (exchange.requestURI.path.endsWith("/heartbeat")) {
                val response = """{"accepted":true,"cancelRequested":false,"fenced":false,"leaseUntil":null}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.write(response)
            } else exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val credentials = root.resolve("credentials").createDirectories()
            credentials.resolve("auth.json").writeText("{}")
            val config = WorkerConfig("http://127.0.0.1:${server.address.port}", "test-worker-token", "usage-test", root, "test-image",
                if (ollama) null else credentials, null, null, emptyMap(), root.resolve("unused.env"), mutableMapOf(), emptyMap(), mistralApiKey = if (mistral) "mistral-subscription-secret" else null, mistralModels = if (mistral) setOf("glm-5-3") else emptySet(), ollamaModels = if (ollama) setOf("test-model") else emptySet())
            val now = Instant.now()
            val execution = ExecutionSelection(if (ollama) "ollama" else if (mistral) "mistral" else "openai", if (mistral) "glm-5-3" else "test-model", if (ollama) ExecutionMode.API else ExecutionMode.SUBSCRIPTION)
            val job = JobView("test-job", if (mistral) "hkh" else "test-tenant", "test-key", JobKind.APPLICATION_WORK, TaskType.STRUCTURED_GENERATION,
                execution, JobStatus.RUNNING, "EXECUTING", 1, 1, null, null, null, null, now, now, null)
            val attempt = AttemptView("test-attempt", job.id, 1, AttemptReason.INITIAL, AttemptStatus.RUNNING, execution, null,
                UsageQuality.UNAVAILABLE, JobUsageSummary(1, UsageQuality.UNAVAILABLE), null, now, null)
            val artifacts = if (requiredArtifact) listOf(OutputArtifactDeclaration("proof", true, setOf("text/plain"))) else emptyList()
            val request = CreateJobRequest("test-key", job.jobKind, job.taskType, execution, JobInput("Test instruction"), OutputContract(artifacts = artifacts))
            val claim = ClaimedJob(job, attempt, "test-fence", now.plusSeconds(120), now.plusSeconds(3600), request)
            val executor = V2WorkerExecutor(config, mapper, "boot", startContainer = { process ->
                if (ollama) {
                    assertThat(process.command()).contains("AR_ENGINE=OLLAMA", "AR_OLLAMA_BASE_URL=http://host.docker.internal:11434/v1", "AR_OLLAMA_CONTEXT_WINDOW=65536")
                    assertThat(process.command().joinToString(" ")).doesNotContain("credential-source", "OPENAI_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "MISTRAL_API_KEY")
                }
                if (mistral) {
                    assertThat(process.command()).contains("MISTRAL_API_KEY", "AR_ENGINE=MISTRAL")
                    assertThat(process.command().joinToString(" ")).doesNotContain("mistral-subscription-secret", "credential-source")
                    assertThat(process.environment()["MISTRAL_API_KEY"]).isEqualTo("mistral-subscription-secret")
                    assertThat(process.command().any { it.startsWith("AR_TIME_LIMIT_SECONDS=") }).isTrue()
                    failureCode?.let { root.resolve("v2-test-job-test-attempt/job/output/mistral-status.json").writeText("{\"code\":\"$it\"}") }
                }
                output?.let { root.resolve("v2-test-job-test-attempt/job/output/result.json").writeText(it) }
                val event = if (mistral) """{"type":"mistral.usage","request_id":"call","session_id":"session","usage":{"input_tokens":100,"output_tokens":20}}""" else """{"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":20}}"""
                val lines=listOfNotNull(event,limitEvent,progressEvent).joinToString("\n")
                ProcessBuilder("sh", "-c", "printf '%s\\n' '$lines'; ${if (progressEvent != null) "sleep 2; " else ""}exit $exit").start()
            })
            executor.execute(claim)
            if (progressEvent != null) {
                val repairs = calls.filter { it.first.endsWith("/progress") && it.second.path("phase").asText() == "REPAIRING" }
                if (ollama) {
                    assertThat(repairs).hasSize(1)
                    assertThat(repairs.single().second.path("message").asText()).contains("Antwoord herstellen (1/3)")
                    assertThat(calls.any { it.first.endsWith("/heartbeat") }).isTrue()
                } else assertThat(repairs).isEmpty()
            }
            val failed = calls.single { it.first.endsWith("/fail") }
            assertThat(failed.second.path("errorCode").asText()).isEqualTo(expectedError)
            if (mistral) assertThat(failed.second.path("retryable").asBoolean()).isEqualTo(expectedError == "PROVIDER_UNAVAILABLE")
            val usage = calls.filter { it.first.endsWith("/usage-events") }
            assertThat(usage.flatMap { it.second.path("metrics").toList() }.filter { it.path("metric").asText() == "INPUT_TOKENS" }.sumOf { it.path("quantity").asLong() }).isEqualTo(100L)
            assertThat(usage.flatMap { it.second.path("metrics").toList() }.filter { it.path("metric").asText() == "OUTPUT_TOKENS" }.sumOf { it.path("quantity").asLong() }).isEqualTo(20L)
            assertThat(calls.indexOf(usage.first())).isLessThan(calls.indexOf(failed))
            if (limitEvent!=null) {
                val observed=calls.single { it.first.endsWith("/provider-limits") }
                assertThat(calls.indexOf(observed)).isLessThan(calls.indexOf(failed))
                assertThat(failed.second.path("providerLimits").single().path("eventId").asText())
                    .isEqualTo(observed.second.path("observation").path("eventId").asText())
            }
            assertThat(root.resolve("v2-test-job-test-attempt")).doesNotExist()
        } finally {
            server.stop(0)
        }
    }
}
