package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.agentruntime.contracts.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.io.path.readText
import java.time.Instant

class WorkerSupportTest {
    @Test
    fun `environment files override defaults while process environment stays authoritative`(@TempDir root: Path) {
        root.resolve("properties.default.env").writeText("AR_SERVER_URL=http://default\nAR_WORKER_TOKEN=default\n")
        root.resolve("properties.env").writeText("AR_SERVER_URL=http://properties\n")
        root.resolve("secrets.env").writeText("AR_WORKER_TOKEN=secret\n")
        val values = EnvFiles.load(root)
        assertThat(values["AR_SERVER_URL"]).isEqualTo("http://properties")
        assertThat(values["AR_WORKER_TOKEN"]).isEqualTo("secret")
    }

    @Test
    fun `safe messages redact credential shaped values`() {
        assertThat(safe("Bearer abc.def token=very-secret password: nope"))
            .doesNotContain("abc.def", "very-secret", "nope")
    }

    @Test
    fun `journal encrypts fencing token and can recover claim`(@TempDir root: Path) {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val request = CreateJobRequest(JobKind.APPLICATION_WORK, "idem", "product-factory-default", "job", Provider.CODEX, "model", "1", "1", "do it", mapper.createObjectNode())
        val view = JobView("11111111-1111-1111-1111-111111111111", "product-factory", JobKind.APPLICATION_WORK, "idem", "product-factory-default", "job", Provider.CODEX, "model", JobStatus.RUNNING, "LEASED", 1, 3, 50, null, null, null, null, Instant.now(), Instant.now(), null)
        val claim = ClaimedJob(view, "attempt", "plain-fencing-token", Instant.now().plusSeconds(120), request)
        val journal = WorkerJournal(root, mapper)
        journal.save(JournalEntry(claim))
        val raw = root.resolve("journal/${view.id}.journal").readText()
        assertThat(raw).doesNotContain("plain-fencing-token")
        assertThat(journal.entries().single().claim.fencingToken).isEqualTo("plain-fencing-token")
    }
}
