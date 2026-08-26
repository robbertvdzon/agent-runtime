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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists

class WorkerSupportTest {
    @Test
    fun `environment files override defaults while process environment stays authoritative`(@TempDir root: Path) {
        root.resolve("properties.default.env").writeText("AR_SERVER_URL=http://default\nAR_WORKER_TOKEN=default\n")
        root.resolve("properties.env").writeText("AR_SERVER_URL=http://properties\n")
        root.resolve("secrets.env").writeText("AR_WORKER_TOKEN=secret\n")
        runCatching { Files.setPosixFilePermissions(root.resolve("secrets.env"), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
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
        val request = CreateJobRequest(JobKind.APPLICATION_WORK, "idem", Provider.CODEX, "model", "do it")
        val view = JobView("11111111-1111-1111-1111-111111111111", "product-factory", JobKind.APPLICATION_WORK, "idem", Provider.CODEX, "model", JobStatus.RUNNING, "LEASED", 1, 3, 50, null, null, null, null, Instant.now(), Instant.now(), null)
        val claim = ClaimedJob(view, "attempt", "plain-fencing-token", Instant.now().plusSeconds(120), Instant.now().plusSeconds(3600), request)
        val journal = WorkerJournal(root, mapper)
        journal.save(JournalEntry(claim))
        val raw = root.resolve("journal/${view.id}.journal").readText()
        assertThat(raw).doesNotContain("plain-fencing-token")
        assertThat(journal.entries().single().claim.fencingToken).isEqualTo("plain-fencing-token")
    }

    @Test
    fun `project credential parser rejects duplicates forbidden names symlinks and broad mode`(@TempDir root: Path) {
        val file = root.resolve("project-credentials.env")
        file.writeText("HKH__USER=one\nHKH__USER=two\n")
        runCatching { Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        assertThatThrownBy { ProjectCredentials.load(file) }.isInstanceOf(IllegalArgumentException::class.java)
        file.writeText("AR__TOKEN=blocked\n")
        assertThatThrownBy { ProjectCredentials.load(file) }.isInstanceOf(IllegalArgumentException::class.java)
        runCatching { Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ)) }
        assertThatThrownBy { ProjectCredentials.load(file) }.isInstanceOf(IllegalArgumentException::class.java)
        file.toFile().delete()
        val target = root.resolve("target.env").also { it.writeText("HKH__USER=value\n") }
        root.resolve("project-credentials.env").createSymbolicLinkPointingTo(target)
        assertThatThrownBy { ProjectCredentials.load(root.resolve("project-credentials.env")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `startup cleanup removes only orphan attempt directories`(@TempDir root: Path) {
        val active = root.resolve("active").also(Files::createDirectories)
        val orphan = root.resolve("orphan").also(Files::createDirectories)
        root.resolve("journal").also(Files::createDirectories)
        cleanupOrphanAttempts(root, setOf("active"))
        assertThat(active.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
        assertThat(root.resolve("journal").exists()).isTrue()
    }
}
