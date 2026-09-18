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

    private fun exercise(root: Path, expectedError: String, exit: Int, output: String?, requiredArtifact: Boolean = false) {
        val calls = CopyOnWriteArrayList<Pair<String, JsonNode>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += exchange.requestURI.path to mapper.readTree(exchange.requestBody)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val credentials = root.resolve("credentials").createDirectories()
            credentials.resolve("auth.json").writeText("{}")
            val config = WorkerConfig("http://127.0.0.1:${server.address.port}", "test-worker-token", "usage-test", root, "test-image",
                credentials, null, null, emptyMap(), root.resolve("unused.env"), mutableMapOf(), emptyMap())
            val now = Instant.now()
            val execution = ExecutionSelection("openai", "test-model", ExecutionMode.SUBSCRIPTION)
            val job = JobView("test-job", "test-tenant", "test-key", JobKind.APPLICATION_WORK, TaskType.STRUCTURED_GENERATION,
                execution, JobStatus.RUNNING, "EXECUTING", 1, 1, null, null, null, null, now, now, null)
            val attempt = AttemptView("test-attempt", job.id, 1, AttemptReason.INITIAL, AttemptStatus.RUNNING, execution, null,
                UsageQuality.UNAVAILABLE, JobUsageSummary(1, UsageQuality.UNAVAILABLE), null, now, null)
            val artifacts = if (requiredArtifact) listOf(OutputArtifactDeclaration("proof", true, setOf("text/plain"))) else emptyList()
            val request = CreateJobRequest("test-key", job.jobKind, job.taskType, execution, JobInput("Test instruction"), OutputContract(artifacts = artifacts))
            val claim = ClaimedJob(job, attempt, "test-fence", now.plusSeconds(120), now.plusSeconds(3600), request)
            val executor = V2WorkerExecutor(config, mapper, "boot", startContainer = {
                output?.let { root.resolve("v2-test-job-test-attempt/job/output/result.json").writeText(it) }
                ProcessBuilder("sh", "-c", "printf '%s\\n' '{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}'; exit $exit").start()
            })
            executor.execute(claim)
            val failed = calls.single { it.first.endsWith("/fail") }
            assertThat(failed.second.path("errorCode").asText()).isEqualTo(expectedError)
            val usage = calls.filter { it.first.endsWith("/usage-events") }
            assertThat(usage.flatMap { it.second.path("metrics").toList() }.filter { it.path("metric").asText() == "INPUT_TOKENS" }.sumOf { it.path("quantity").asLong() }).isEqualTo(100L)
            assertThat(usage.flatMap { it.second.path("metrics").toList() }.filter { it.path("metric").asText() == "OUTPUT_TOKENS" }.sumOf { it.path("quantity").asLong() }).isEqualTo(20L)
            assertThat(calls.indexOf(usage.first())).isLessThan(calls.indexOf(failed))
            assertThat(root.resolve("v2-test-job-test-attempt")).doesNotExist()
        } finally {
            server.stop(0)
        }
    }
}
