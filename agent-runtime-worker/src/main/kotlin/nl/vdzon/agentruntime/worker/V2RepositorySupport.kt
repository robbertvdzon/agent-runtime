package nl.vdzon.agentruntime.worker

import nl.vdzon.agentruntime.contracts.v2.RepositoryCheckout
import nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationStatus
import nl.vdzon.agentruntime.contracts.v2.RepositoryResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

data class V2RepositoryState(
    val alias: String,
    val branch: String,
    val checkoutCommitSha: String,
    val refs: String,
    val localConfig: String,
    val publicationUrl: String,
)

enum class RemotePublicationState { PRESENT, ABSENT, AMBIGUOUS }

class V2RepositorySupport(private val repositoryAliases: Map<String, String>) {
    fun checkout(checkout: RepositoryCheckout, workspace: Path, workRoot: Path): V2RepositoryState {
        val url = repositoryAliases[checkout.alias]
            ?: throw JobFailure("UNKNOWN_REPOSITORY_ALIAS", "Worker does not know repository alias ${checkout.alias}.", false)
        val remoteHead = remoteHead(url, checkout.branch, workRoot)
            ?: throw JobFailure("REMOTE_BRANCH_NOT_FOUND", "The configured remote branch does not exist.", false)
        execute(listOf("git", "clone", "--branch", checkout.branch, "--single-branch", "--", url, workspace.toString()), workRoot, 300, "GIT_CLONE_FAILED", true)
        val branch = execute(listOf("git", "branch", "--show-current"), workspace, 30).trim()
        val upstream = execute(listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"), workspace, 30).trim()
        val head = execute(listOf("git", "rev-parse", "HEAD"), workspace, 30).trim()
        if (branch != checkout.branch || upstream != "origin/${checkout.branch}" || head != remoteHead) {
            throw JobFailure("INVALID_REPOSITORY_CHECKOUT", "The checked out branch or upstream differs from the requested remote branch.", false)
        }
        execute(listOf("git", "pull", "--ff-only"), workspace, 120, "GIT_CLONE_FAILED", true)
        val agentVisibleUrl = withoutCredentials(url)
        if (agentVisibleUrl != url) execute(listOf("git", "remote", "set-url", "origin", agentVisibleUrl), workspace, 30)
        return capture(checkout, workspace, url)
    }

    fun capture(checkout: RepositoryCheckout, workspace: Path, publicationUrl: String = execute(listOf("git", "remote", "get-url", "origin"), workspace, 30).trim()): V2RepositoryState = V2RepositoryState(
        checkout.alias,
        execute(listOf("git", "branch", "--show-current"), workspace, 30).trim(),
        execute(listOf("git", "rev-parse", "HEAD"), workspace, 30).trim(),
        execute(listOf("git", "show-ref"), workspace, 30, allowEmptyFailure = true),
        execute(listOf("git", "config", "--local", "--list", "--null"), workspace, 30),
        publicationUrl,
    )

    fun verifyAgentDidNotMutate(state: V2RepositoryState, workspace: Path) {
        val actual = V2RepositoryState(
            state.alias,
            execute(listOf("git", "branch", "--show-current"), workspace, 30).trim(),
            execute(listOf("git", "rev-parse", "HEAD"), workspace, 30).trim(),
            execute(listOf("git", "show-ref"), workspace, 30, allowEmptyFailure = true),
            execute(listOf("git", "config", "--local", "--list", "--null"), workspace, 30),
            state.publicationUrl,
        )
        if (actual != state) throw JobFailure("GIT_METADATA_MUTATED", "Git branch, HEAD, refs or local configuration changed during agent execution.", false)
    }

    fun prepareCommit(
        checkout: RepositoryCheckout,
        state: V2RepositoryState,
        workspace: Path,
        jobId: String,
        sensitiveValues: Collection<String> = emptySet(),
    ): RepositoryResult {
        validateChangedPaths(workspace, sensitiveValues)
        val status = execute(listOf("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"), workspace, 30)
        if (status.isEmpty()) return RepositoryResult(checkout.alias, checkout.branch, state.checkoutCommitSha, RepositoryPublicationStatus.NO_CHANGES)
        execute(listOf("git", "add", "--all"), workspace, 30)
        val diffStat = execute(listOf("git", "diff", "--cached", "--stat", "HEAD"), workspace, 30).take(20_000)
        val staged = run(listOf("git", "diff", "--cached", "--quiet", "HEAD"), workspace, 30)
        if (staged.exitCode == 0) return RepositoryResult(checkout.alias, checkout.branch, state.checkoutCommitSha, RepositoryPublicationStatus.NO_CHANGES)
        execute(
            listOf("git", "-c", "user.name=Agent Runtime", "-c", "user.email=agent-runtime@localhost", "commit", "-m", "agent-runtime: job $jobId", "-m", "Agent-Runtime-Job: $jobId"),
            workspace, 60,
        )
        val commitSha = execute(listOf("git", "rev-parse", "HEAD"), workspace, 30).trim()
        return RepositoryResult(checkout.alias, checkout.branch, state.checkoutCommitSha, RepositoryPublicationStatus.PUSHED, commitSha, diffStat)
    }

    fun push(checkout: RepositoryCheckout, state: V2RepositoryState, workspace: Path) {
        val currentRemote = remoteHead(state.publicationUrl, checkout.branch, workspace)
        if (currentRemote != state.checkoutCommitSha) throw JobFailure("BRANCH_CHANGED", "The remote story branch changed during agent execution.", false)
        val result = run(listOf("git", "push", state.publicationUrl, "HEAD:refs/heads/${checkout.branch}"), workspace, 180)
        if (result.exitCode != 0) {
            throw JobFailure("GIT_PUSH_FAILED", "Repository push failed; remote state must be reconciled before retrying.", true)
        }
    }

    fun remotePublicationState(state: V2RepositoryState, workspace: Path, commitSha: String, jobId: String): RemotePublicationState {
        val remoteHead = remoteHead(state.publicationUrl, state.branch, workspace)
        if (remoteHead == state.checkoutCommitSha) return RemotePublicationState.ABSENT
        execute(
            listOf("git", "fetch", "--no-tags", "--force", "--", state.publicationUrl, "refs/heads/${state.branch}"),
            workspace, 120, "GIT_CLONE_FAILED", true,
        )
        val exists = run(listOf("git", "cat-file", "-e", "$commitSha^{commit}"), workspace, 30).exitCode == 0
        if (!exists) return RemotePublicationState.AMBIGUOUS
        val ancestor = run(listOf("git", "merge-base", "--is-ancestor", commitSha, "FETCH_HEAD"), workspace, 30).exitCode == 0
        if (!ancestor) return RemotePublicationState.AMBIGUOUS
        val body = execute(listOf("git", "show", "-s", "--format=%B", commitSha), workspace, 30)
        return if (body.lineSequence().any { it.trim() == "Agent-Runtime-Job: $jobId" }) RemotePublicationState.PRESENT else RemotePublicationState.AMBIGUOUS
    }

    fun execute(
        argv: List<String>, cwd: Path, timeoutSeconds: Long, errorCode: String = "COMMAND_FAILED",
        retryable: Boolean = true, allowEmptyFailure: Boolean = false,
    ): String {
        val result = run(argv, cwd, timeoutSeconds)
        if (result.exitCode != 0 && !(allowEmptyFailure && result.output.isBlank())) {
            throw JobFailure(errorCode, "Controlled ${argv.first()} command failed: ${safeGitOutput(result.output)}", retryable)
        }
        return result.output
    }

    private fun remoteHead(url: String, branch: String, cwd: Path): String? {
        val result = run(listOf("git", "ls-remote", "--exit-code", "--heads", "--", url, "refs/heads/$branch"), cwd, 120)
        if (result.exitCode == 2) return null
        if (result.exitCode != 0) throw JobFailure("GIT_CLONE_FAILED", "Remote repository or branch could not be queried.", true)
        return result.output.trim().substringBefore('\t').takeIf { it.matches(Regex("[0-9a-f]{40}")) }
    }

    private fun withoutCredentials(value: String): String = runCatching {
        val uri = java.net.URI.create(value)
        if (uri.rawUserInfo.isNullOrBlank()) value else java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
    }.getOrDefault(value)

    private fun validateChangedPaths(workspace: Path, sensitiveValues: Collection<String>) {
        val raw = execute(listOf("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"), workspace, 30)
        val records = raw.split('\u0000').filter(String::isNotEmpty)
        var index = 0
        while (index < records.size) {
            val record = records[index]
            if (record.length < 4) throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Git returned an invalid changed path.", false)
            validatePath(workspace, record.substring(3), sensitiveValues)
            if (record[0] in setOf('R', 'C') || record[1] in setOf('R', 'C')) {
                index++
                if (index >= records.size) throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Git returned an incomplete renamed path.", false)
                validatePath(workspace, records[index], sensitiveValues)
            }
            index++
        }
    }

    private fun validatePath(workspace: Path, relative: String, sensitiveValues: Collection<String>) {
        val path = workspace.resolve(relative).normalize()
        if (!path.startsWith(workspace) || relative.startsWith('/') || relative.contains('\u0000')) throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Repository output contains an unsafe path.", false)
        if (path.exists(LinkOption.NOFOLLOW_LINKS) && path.isSymbolicLink()) throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Repository output contains a symbolic link.", false)
        if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && path.fileSize() > 20L * 1024 * 1024) throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Repository output contains a file over 20 MiB.", false)
        if (path.fileName?.toString()?.lowercase() in FORBIDDEN_NAMES) throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Repository output contains a forbidden secret filename.", false)
        if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && SecretRedactor.contains(Files.readAllBytes(path), sensitiveValues)) {
            throw JobFailure("UNSAFE_REPOSITORY_OUTPUT", "Repository output contains a selected sensitive value.", false)
        }
    }

    private fun safeGitOutput(value: String): String = repositoryAliases.values.fold(safe(value)) { output, url ->
        output.replace(url, withoutCredentials(url))
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun run(argv: List<String>, cwd: Path, timeoutSeconds: Long): CommandResult {
        val process = ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().use { input ->
            val buffer = CharArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.length < 100_000) output.append(buffer, 0, minOf(count, 100_000 - output.length))
            }
        } }.apply { start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw JobFailure("COMMAND_TIMEOUT", "Controlled ${argv.first()} command timed out.", true)
        }
        reader.join(5_000)
        return CommandResult(process.exitValue(), output.toString())
    }

    companion object {
        private val FORBIDDEN_NAMES = setOf("secrets.env", ".env", "id_rsa", "id_ed25519", "properties.env", "project-credentials.env")
    }
}
