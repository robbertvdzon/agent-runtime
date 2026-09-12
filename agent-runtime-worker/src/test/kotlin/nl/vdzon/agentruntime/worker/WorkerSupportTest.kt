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
import kotlin.io.path.createDirectories
import nl.vdzon.agentruntime.contracts.v2.RepositoryCheckout
import nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationMode
import nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationStatus

class WorkerSupportTest {
    @Test
    fun `environment files override defaults while process environment stays authoritative`(@TempDir root: Path) {
        root.resolve("properties.default.env").writeText("AR_SERVER_URL=http://default\nAR_WORKER_TOKEN=default\n")
        root.resolve("properties.env").writeText("AR_SERVER_URL=http://properties\nAR_WORKER_TOKEN=secret\n")
        root.resolve("secrets.env").writeText("AR_WORKER_TOKEN=legacy-value-that-must-be-ignored\n")
        runCatching { Files.setPosixFilePermissions(root.resolve("properties.env"), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        val values = EnvFiles.load(root)
        assertThat(values["AR_SERVER_URL"]).isEqualTo("http://properties")
        assertThat(values["AR_WORKER_TOKEN"]).isEqualTo("secret")
    }

    @Test
    fun `worker properties must be an owner-only regular file`(@TempDir root: Path) {
        val properties = root.resolve("properties.env")
        properties.writeText("AR_WORKER_TOKEN=secret\n")
        runCatching {
            Files.setPosixFilePermissions(properties, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
            ))
        }
        assertThatThrownBy { EnvFiles.load(root) }.isInstanceOf(IllegalArgumentException::class.java)

        properties.toFile().delete()
        val target = root.resolve("target.env").also { it.writeText("AR_WORKER_TOKEN=secret\n") }
        properties.createSymbolicLinkPointingTo(target)
        assertThatThrownBy { EnvFiles.load(root) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `safe messages redact credential shaped values`() {
        SecretRedactor.configure(listOf("subscription-oauth-value"))
        assertThat(safe("Bearer abc.def token=very-secret password: nope subscription-oauth-value"))
            .doesNotContain("abc.def", "very-secret", "nope", "subscription-oauth-value")
        SecretRedactor.configure(emptyList())
    }

    @Test
    fun `claude subscription token does not require a credential directory`() {
        assertThat(ClaudeProviderAdapter(null, "subscription-oauth-value").credentials()).isNull()
        assertThatThrownBy { ClaudeProviderAdapter(null, null).credentials() }
            .isInstanceOf(JobFailure::class.java)
    }

    @Test
    fun `credential policy distinguishes secrets from ordinary project configuration`() {
        assertThat(ProjectCredentialPolicy.isSensitive("PF__PASSWORD", "value")).isTrue()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__OPENSHIFT_KUBECONFIG_BASE64", "value")).isTrue()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__FIREBASE_CREDENTIALS_JSON", "value")).isTrue()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__DATABASE_URL", "postgresql://user:password@localhost/db")).isTrue()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__DATABASE_URL", "postgresql://database.example/db")).isFalse()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__USERNAME", "robbert")).isFalse()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__DATABASE_SCHEMA", "software_factory")).isFalse()
        assertThat(ProjectCredentialPolicy.isSensitive("PF__COOKIE_SECURE", "true")).isFalse()
    }

    @Test
    fun `output blocking uses only sensitive values selected for the current job`() {
        val credentials = mapOf(
            "PF__USERNAME" to "robbert",
            "PF__PASSWORD" to "robbert",
            "PF__COOKIE_SECURE" to "true",
            "PF__DATABASE_SCHEMA" to "software_factory",
        )
        val ordinaryValues = selectedSensitiveValues(
            listOf("PF__USERNAME", "PF__COOKIE_SECURE", "PF__DATABASE_SCHEMA"),
            credentials,
        )
        assertThat(ordinaryValues).isEmpty()
        assertThat(SecretRedactor.contains("worker robberts-macbook is online", ordinaryValues)).isFalse()
        assertThat(SecretRedactor.contains("result was true", ordinaryValues)).isFalse()

        val selectedPassword = selectedSensitiveValues(listOf("PF__PASSWORD"), credentials)
        assertThat(selectedPassword).containsExactly("robbert")
        assertThat(SecretRedactor.contains("worker robberts-macbook is online", selectedPassword)).isTrue()
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
        val readOnly = orphan.resolve("readonly").also(Files::createDirectories)
        readOnly.resolve("prompt.md").writeText("temporary input")
        runCatching { Files.setPosixFilePermissions(readOnly, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)) }
        root.resolve("journal").also(Files::createDirectories)
        cleanupOrphanAttempts(root, setOf("active"))
        assertThat(active.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
        assertThat(root.resolve("journal").exists()).isTrue()
    }

    @Test
    fun `v2 repository jobs checkout and push the exact existing story branch`(@TempDir root: Path) {
        val fixture=gitFixture(root)
        val support=V2RepositorySupport(mapOf("test-repository" to fixture.remote.toString()))
        val checkout=RepositoryCheckout("test-repository",fixture.branch,RepositoryPublicationMode.COMMIT_AND_PUSH)
        val first=root.resolve("first")
        val state=support.checkout(checkout,first,root)
        assertThat(state.branch).isEqualTo(fixture.branch)
        assertThat(state.checkoutCommitSha).isEqualTo(fixture.initialStorySha)
        first.resolve("implemented.txt").writeText("implemented\n")
        support.verifyAgentDidNotMutate(state,first)
        val publication=support.prepareCommit(checkout,state,first,"job-123")
        assertThat(publication.publicationStatus).isEqualTo(RepositoryPublicationStatus.PUSHED)
        support.push(checkout,state,first)

        val second=root.resolve("second")
        val secondState=support.checkout(checkout,second,root)
        assertThat(secondState.checkoutCommitSha).isEqualTo(publication.commitSha)
        assertThat(second.resolve("implemented.txt").readText()).isEqualTo("implemented\n")
        assertThat(support.remotePublicationState(state,second,publication.commitSha!!,"job-123")).isEqualTo(RemotePublicationState.PRESENT)
        assertThat(git(second,"log","--format=%s",fixture.initialStorySha+"..HEAD").lineSequence().count(String::isNotBlank)).isEqualTo(1)
    }

    @Test
    fun `v2 repository no changes missing branch and metadata mutation have stable outcomes`(@TempDir root: Path) {
        val fixture=gitFixture(root)
        val support=V2RepositorySupport(mapOf("test-repository" to fixture.remote.toString()))
        val checkout=RepositoryCheckout("test-repository",fixture.branch,RepositoryPublicationMode.COMMIT_AND_PUSH)
        val workspace=root.resolve("workspace")
        val state=support.checkout(checkout,workspace,root)
        assertThat(support.prepareCommit(checkout,state,workspace,"job-empty").publicationStatus).isEqualTo(RepositoryPublicationStatus.NO_CHANGES)
        git(workspace,"checkout","-b","forbidden-agent-branch")
        assertThatThrownBy { support.verifyAgentDidNotMutate(state,workspace) }
            .isInstanceOf(JobFailure::class.java).extracting("code").isEqualTo("GIT_METADATA_MUTATED")

        val missing=RepositoryCheckout("test-repository","software-factory/missing",RepositoryPublicationMode.COMMIT_AND_PUSH)
        assertThatThrownBy { support.checkout(missing,root.resolve("missing"),root) }
            .isInstanceOf(JobFailure::class.java).extracting("code").isEqualTo("REMOTE_BRANCH_NOT_FOUND")
    }

    @Test
    fun `v2 repository never force pushes when story branch changes remotely`(@TempDir root: Path) {
        val fixture=gitFixture(root)
        val support=V2RepositorySupport(mapOf("test-repository" to fixture.remote.toString()))
        val checkout=RepositoryCheckout("test-repository",fixture.branch,RepositoryPublicationMode.COMMIT_AND_PUSH)
        val stale=root.resolve("stale");val staleState=support.checkout(checkout,stale,root)
        val concurrent=root.resolve("concurrent");support.checkout(checkout,concurrent,root)
        concurrent.resolve("human.txt").writeText("human\n");git(concurrent,"add","--all");git(concurrent,"-c","user.name=Human","-c","user.email=human@example.test","commit","-m","human change");git(concurrent,"push","origin","HEAD:refs/heads/${fixture.branch}")
        stale.resolve("agent.txt").writeText("agent\n");support.prepareCommit(checkout,staleState,stale,"job-stale")
        assertThatThrownBy { support.push(checkout,staleState,stale) }
            .isInstanceOf(JobFailure::class.java).extracting("code").isEqualTo("BRANCH_CHANGED")
        val verify=root.resolve("verify");support.checkout(checkout,verify,root)
        assertThat(verify.resolve("human.txt").exists()).isTrue()
        assertThat(verify.resolve("agent.txt").exists()).isFalse()
    }

    @Test
    fun `v2 publication reconciliation proves whether the intended commit is remote`(@TempDir root: Path) {
        val fixture=gitFixture(root)
        val support=V2RepositorySupport(mapOf("test-repository" to fixture.remote.toString()))
        val checkout=RepositoryCheckout("test-repository",fixture.branch,RepositoryPublicationMode.COMMIT_AND_PUSH)
        val workspace=root.resolve("workspace");val state=support.checkout(checkout,workspace,root)
        workspace.resolve("candidate.txt").writeText("candidate\n")
        val publication=support.prepareCommit(checkout,state,workspace,"job-reconcile")
        assertThat(support.remotePublicationState(state,workspace,publication.commitSha!!,"job-reconcile")).isEqualTo(RemotePublicationState.ABSENT)
        support.push(checkout,state,workspace)
        assertThat(support.remotePublicationState(state,workspace,publication.commitSha!!,"job-reconcile")).isEqualTo(RemotePublicationState.PRESENT)
    }

    @Test
    fun `v2 repository blocks unsafe output paths before staging`(@TempDir root: Path) {
        val fixture=gitFixture(root)
        val support=V2RepositorySupport(mapOf("test-repository" to fixture.remote.toString()))
        val checkout=RepositoryCheckout("test-repository",fixture.branch,RepositoryPublicationMode.COMMIT_AND_PUSH)
        val workspace=root.resolve("workspace");val state=support.checkout(checkout,workspace,root)
        workspace.resolve("secrets.env").writeText("TOKEN=unsafe\n")
        assertThatThrownBy { support.prepareCommit(checkout,state,workspace,"job-secret") }
            .isInstanceOf(JobFailure::class.java).extracting("code").isEqualTo("UNSAFE_REPOSITORY_OUTPUT")

        Files.deleteIfExists(workspace.resolve("secrets.env"))
        workspace.resolve("ordinary.txt").writeText("the-selected-secret\n")
        assertThatThrownBy { support.prepareCommit(checkout,state,workspace,"job-secret-value",setOf("the-selected-secret")) }
            .isInstanceOf(JobFailure::class.java).extracting("code").isEqualTo("UNSAFE_REPOSITORY_OUTPUT")
    }

    @Test
    fun `repository verification config is strict safe and selects commands by changed path`(@TempDir root: Path) {
        root.resolve(".factory").createDirectories()
        root.resolve("backend").createDirectories()
        root.resolve("frontend").createDirectories()
        root.resolve(".factory/verification.yaml").writeText(
            """
            version: 1
            commands:
              - id: backend-verify
                pathPrefixes: [backend/, pom.xml]
                argv: [mvn, -B, verify]
                workingDirectory: backend
                timeoutSeconds: 1800
              - id: frontend-verify
                pathPrefixes: [frontend/]
                agentRunnable: false
                argv: [flutter, test]
                workingDirectory: frontend
                timeoutSeconds: 900
            """.trimIndent(),
        )
        val support = V2VerificationSupport()
        val loaded = support.load(root)
        assertThat(loaded).isInstanceOf(VerificationConfigLoad.Loaded::class.java)
        val config = (loaded as VerificationConfigLoad.Loaded).config
        assertThat(support.selectedCommands(config, setOf("backend/src/App.kt")).map { it.id }).containsExactly("backend-verify")
        assertThat(support.selectedCommands(config, setOf("README.md"))).isEmpty()
        assertThat(support.selectedCommands(config, null).map { it.id }).containsExactly("backend-verify")
        assertThat(support.commandWorkingDirectory(root, config.commands.first())).isEqualTo(root.resolve("backend"))

        root.resolve(".factory/verification.yaml").writeText(
            """
            version: 1
            commands:
              - id: unsafe
                argv: [mvn, verify]
                workingDirectory: ../outside
                timeoutSeconds: 10
            """.trimIndent(),
        )
        assertThat(support.load(root)).isInstanceOf(VerificationConfigLoad.Invalid::class.java)
    }

    @Test
    fun `repository verification distinguishes missing invalid and symlinked config`(@TempDir root: Path) {
        val support = V2VerificationSupport()
        assertThat(support.load(root)).isEqualTo(VerificationConfigLoad.Missing)
        root.resolve(".factory").createDirectories()
        root.resolve(".factory/verification.yaml").writeText("version: 2\ncommands: []\n")
        assertThat(support.load(root)).isInstanceOf(VerificationConfigLoad.Invalid::class.java)
        Files.deleteIfExists(root.resolve(".factory/verification.yaml"))
        val outside = root.resolve("outside.yaml").also { it.writeText("version: 1\ncommands: []\n") }
        root.resolve(".factory/verification.yaml").createSymbolicLinkPointingTo(outside)
        assertThat(support.load(root)).isInstanceOf(VerificationConfigLoad.Invalid::class.java)
    }

    private data class GitFixture(val remote:Path,val branch:String,val initialStorySha:String)

    private fun gitFixture(root:Path):GitFixture {
        val remote=root.resolve("remote.git");git(root,"init","--bare",remote.toString())
        val seed=root.resolve("seed");seed.createDirectories();git(seed,"init");git(seed,"config","user.name","Test");git(seed,"config","user.email","test@example.test")
        seed.resolve("README.md").writeText("main\n");git(seed,"add","README.md");git(seed,"commit","-m","initial");git(seed,"branch","-M","main");git(seed,"remote","add","origin",remote.toString());git(seed,"push","-u","origin","main")
        val branch="software-factory/SF-123";git(seed,"checkout","-b",branch);seed.resolve("story.txt").writeText("story\n");git(seed,"add","story.txt");git(seed,"commit","-m","story branch");git(seed,"push","-u","origin",branch)
        return GitFixture(remote,branch,git(seed,"rev-parse","HEAD").trim())
    }

    private fun git(cwd:Path,vararg args:String):String {
        val process=ProcessBuilder(listOf("git")+args).directory(cwd.toFile()).redirectErrorStream(true).start()
        val output=process.inputStream.bufferedReader().readText()
        check(process.waitFor()==0){"git ${args.joinToString(" ")} failed: $output"}
        return output
    }
}

class WorkerSlotsTest {
    private fun job(kind: nl.vdzon.agentruntime.contracts.v2.JobKind, task: nl.vdzon.agentruntime.contracts.v2.TaskType) =
        jacksonObjectMapper().registerModule(JavaTimeModule()).convertValue(mapOf("id" to "j", "tenantId" to "t", "idempotencyKey" to "k", "jobKind" to kind, "taskType" to task,
            "execution" to mapOf("vendorId" to "local", "model" to "m", "mode" to "LOCAL"), "status" to "RUNNING", "phase" to "EXECUTING", "attemptCount" to 1, "maxAttempts" to 3,
            "createdAt" to "2026-01-01T00:00:00Z", "updatedAt" to "2026-01-01T00:00:00Z"), nl.vdzon.agentruntime.contracts.v2.JobView::class.java)

    @Test
    fun `claims unfiltered when idle and only for classes with free slots when busy`() {
        val slots = WorkerSlots(2, 1, 1)
        assertThat(slots.total).isEqualTo(4)
        assertThat(slots.claimPlan()).containsExactly(WorkerClaimFilter(null, null))
        val repository = slots.classOf(job(nl.vdzon.agentruntime.contracts.v2.JobKind.REPOSITORY_WORK, nl.vdzon.agentruntime.contracts.v2.TaskType.REPOSITORY_AGENT))
        assertThat(repository).isEqualTo(WorkerSlotClass.REPOSITORY)
        slots.acquire(repository)
        val plan = slots.claimPlan()
        assertThat(plan.map { it.jobKinds }).doesNotContain(setOf(nl.vdzon.agentruntime.contracts.v2.JobKind.REPOSITORY_WORK))
        assertThat(plan).hasSize(2)
        assertThat(slots.classOf(job(nl.vdzon.agentruntime.contracts.v2.JobKind.APPLICATION_WORK, nl.vdzon.agentruntime.contracts.v2.TaskType.TRANSCRIPTION))).isEqualTo(WorkerSlotClass.TRANSCRIPTION)
        slots.acquire(WorkerSlotClass.APPLICATION); slots.acquire(WorkerSlotClass.APPLICATION); slots.acquire(WorkerSlotClass.TRANSCRIPTION)
        assertThat(slots.claimPlan()).isEmpty()
        slots.release(WorkerSlotClass.APPLICATION)
        assertThat(slots.claimPlan().single().taskTypes).doesNotContain(nl.vdzon.agentruntime.contracts.v2.TaskType.TRANSCRIPTION)
    }

    @Test
    fun `whisper model configuration is parsed from name path pairs`() {
        assertThat(WorkerConfig.whisperModels("large-v3-turbo=/tmp/a.bin, small = /tmp/b.bin,broken"))
            .isEqualTo(mapOf("large-v3-turbo" to Path.of("/tmp/a.bin"), "small" to Path.of("/tmp/b.bin")))
    }
}
