package nl.vdzon.agentruntime.worker

import nl.vdzon.agentruntime.contracts.ExecutionCredentialPolicy
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

class ExecutionAccessTest {
    @Test fun `production and cluster credentials cannot be selected`() {
        listOf("PF__DEBUG_TOKEN", "PF__PRODUCTION_TOKEN", "PF__DB_PASSWORD", "SF__PREVIEW_CLEANUP_KUBECONFIG_BASE64", "PF__TEST_SESSION_SIGNING_SECRET", "PF__ACCEPTANCE_PROD_TOKEN").forEach {
            assertThat(ExecutionCredentialPolicy.allows(it)).describedAs(it).isFalse()
        }
        listOf("PF__ACCEPTANCE_AGENT_TOKEN", "HKH__PREVIEW_BASE_URL", "PVDD__TEST_USERNAME").forEach {
            assertThat(ExecutionCredentialPolicy.allows(it)).describedAs(it).isTrue()
        }
    }
    @Test fun `legacy credentials are not advertised or mounted`(@TempDir root: Path) {
        val file = root.resolve("credentials.env")
        file.writeText("PF__DB_PASSWORD=production\nPF__ACCEPTANCE_AGENT_TOKEN=test-only\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        assertThat(ProjectCredentials.load(file)).containsOnlyKeys("PF__ACCEPTANCE_AGENT_TOKEN")
    }
    @Test fun `only authentication is mounted without history hooks or config`(@TempDir root: Path) {
        val source=root.resolve("source").createDirectories()
        source.resolve("auth.json").writeText("authentication")
        source.resolve("config.toml").writeText("private mcp credentials")
        source.resolve("sessions").createDirectories().resolve("private.json").writeText("history")
        val result=isolateProviderCredentials(source,root.resolve("task"),"CODEX")
        assertThat(result.listDirectoryEntries().map { it.name }).containsExactly("auth.json")
        assertThat(result.resolve("auth.json").readText()).isEqualTo("authentication")
    }
    @Test fun `symlink cannot expose another credential file`(@TempDir root: Path) {
        val source=root.resolve("source").createDirectories()
        val secret=root.resolve("private").apply { writeText("private") }
        source.resolve("auth.json").createSymbolicLinkPointingTo(secret)
        assertThatThrownBy { isolateProviderCredentials(source,root.resolve("task"),"CODEX") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
