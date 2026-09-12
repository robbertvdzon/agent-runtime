package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.Provider
import nl.vdzon.agentruntime.contracts.v2.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

class V2WorkerExecutor(private val config: WorkerConfig, private val mapper: ObjectMapper, private val bootId: String, private val maxConcurrency: Int = 1) {
    private val client = V2RuntimeClient(config, mapper)
    private val repositories = V2RepositorySupport(config.repositoryAliases)
    private val verification = V2VerificationSupport()
    @Volatile private var enabled = false
    private val transcriber = V2LocalTranscriber(config, client)
    private val capabilities: Set<ExecutorCapability> = buildSet {
        config.advertisedModels[Provider.CODEX].orEmpty().forEach { model ->
            add(ExecutorCapability("openai", model, ExecutionMode.SUBSCRIPTION, setOf(TaskType.STRUCTURED_GENERATION, TaskType.REPOSITORY_AGENT)))
        }
        config.advertisedModels[Provider.CLAUDE].orEmpty().forEach { model ->
            add(ExecutorCapability("anthropic", model, ExecutionMode.SUBSCRIPTION, setOf(TaskType.STRUCTURED_GENERATION, TaskType.REPOSITORY_AGENT)))
        }
        V2LocalTranscriber.availableModels(config).forEach { model ->
            add(ExecutorCapability("local", model, ExecutionMode.LOCAL, setOf(TaskType.TRANSCRIPTION)))
        }
    }

    fun register() {
        if (capabilities.isEmpty()) return
        enabled = runCatching {
            client.register(
                WorkerRegistrationRequest(
                    config.workerId, bootId, capabilities, config.projectCredentials.keys,
                    config.repositoryAliases.keys, maxConcurrency, mapOf("worker" to "0.4.0"),
                ),
            )
            true
        }.getOrElse {
            System.err.println("Agent Runtime v2 is not available yet; continuing with v1: ${safe(it.message)}")
            false
        }
    }

    fun claim(jobKinds: Set<JobKind>? = null, taskTypes: Set<TaskType>? = null, waitSeconds: Int = 2): ClaimedJob? =
        if (!enabled) null else client.claim(ClaimRequest(bootId, capabilities, waitSeconds, jobKinds, taskTypes))

    fun execute(claim: ClaimedJob) {
        if (claim.job.execution.mode == ExecutionMode.LOCAL) return transcriber.execute(claim)
        val root = config.workRoot.resolve("v2-${claim.job.id}-${claim.attempt.id}")
        try {
            root.createDirectories()
            if (reconcilePublication(claim, root)) return
            if (root.exists()) deleteTree(root)
            val workspace = root.resolve("workspace").also(Path::createDirectories)
            val task = root.resolve("job")
            client.progress(claim, "PREPARING", 5, "Preparing streamed inputs and repository checkout.")
            val repositoryState = prepare(claim, workspace, task)
            val started = Instant.now()
            val roundsOutcome = executeAgentRounds(claim, workspace, task, repositoryState)
            val verificationResult = roundsOutcome.verificationResult
            client.progress(claim, "UPLOADING_OUTPUT", 85, "Uploading declared output artifacts.")
            val outputIds = uploadOutputs(claim, task)
            val result = roundsOutcome.result
            val inputBytes = task.resolve("input/objects").takeIf(Path::exists)?.let { inputRoot ->
                Files.walk(inputRoot).use { paths -> paths.filter(Path::isRegularFile).mapToLong(Path::fileSize).sum() }
            } ?: 0L
            val rounds = verificationResult?.agentRounds ?: 1
            client.measuredUsage(claim, ((claim.request.input.instruction.length.toLong() * rounds + inputBytes) / 4).coerceAtLeast(1), ((result.toString().length.toLong() * rounds) / 4).coerceAtLeast(1))
            client.log(claim, LogKind.SYSTEM, "Execution finished in ${Duration.between(started, Instant.now()).seconds} seconds.")
            complete(claim, workspace, repositoryState, result, outputIds, verificationResult)
        } catch (failure: JobFailure) {
            runCatching { client.fail(claim, failure.code, failure.message.orEmpty(), failure.retryable) }
        } catch (error: Exception) {
            runCatching { client.fail(claim, "WORKER_ERROR", safe(error.message), true) }
        } finally {
            runCatching { deleteTree(root) }
        }
    }

    private data class AgentRoundsOutcome(val result: JsonNode, val verificationResult: VerificationResult?)
    private data class ContainerRunResult(val exitCode: Int, val timedOut: Boolean)

    private fun executeAgentRounds(claim: ClaimedJob, workspace: Path, task: Path, repositoryState: V2RepositoryState?): AgentRoundsOutcome {
        val request = claim.request.verification
        val enabled = request?.mode == VerificationMode.REPOSITORY_CONFIG
        val maximumRounds = if (enabled) request!!.maxRepairAttempts + 1 else 1
        val executionDeadline = if (enabled) claim.attemptDeadline.minusSeconds(FINALIZATION_RESERVE_SECONDS) else claim.attemptDeadline
        val basePrompt = task.resolve("input/prompt.md").readText()
        var round = 1
        var lastValidatedResult: JsonNode? = null
        var lastVerificationResult: VerificationResult? = null
        while (true) {
            task.resolve("output/result.json").deleteIfExists()
            client.progress(claim, "EXECUTING", (10 + round * 10).coerceAtMost(55), "Starting agent round $round of $maximumRounds.")
            val container = runContainer(claim, workspace, task, round, executionDeadline)
            if (container.timedOut && lastValidatedResult != null) {
                return AgentRoundsOutcome(lastValidatedResult, timeoutResult(lastVerificationResult, round))
            }
            if (container.exitCode != 0) throw JobFailure(if (container.timedOut) "VERIFICATION_TIMEOUT" else "ENGINE_FAILED", "Provider process exited with code ${container.exitCode}.", !container.timedOut && container.exitCode in setOf(124, 137))
            repositoryState?.let { repositories.verifyAgentDidNotMutate(it, workspace) }
            val validatedResult = readResult(claim, task)
            if (!enabled) return AgentRoundsOutcome(validatedResult, null)

            val changedPaths = runCatching { repositories.changedPaths(workspace) }.getOrElse {
                client.log(claim, LogKind.SYSTEM, "Changed paths could not be determined reliably; all agent-runnable verification commands will run.")
                null
            }
            if (changedPaths?.isEmpty() == true) return AgentRoundsOutcome(validatedResult, null)
            val outcome = when (val loaded = verification.load(workspace)) {
                VerificationConfigLoad.Missing -> VerificationResult(VerificationStatus.CONFIG_MISSING, null, round)
                is VerificationConfigLoad.Invalid -> {
                    client.log(claim, LogKind.SYSTEM, "Repository verification configuration is invalid: ${redact(loaded.reason, 1000)}")
                    VerificationResult(VerificationStatus.CONFIG_INVALID, null, round)
                }
                is VerificationConfigLoad.Loaded -> executeVerification(claim, workspace, task, requireNotNull(repositoryState), loaded.config, changedPaths, round, executionDeadline)
            }
            if (outcome.status in setOf(VerificationStatus.PASSED, VerificationStatus.SKIPPED, VerificationStatus.CONFIG_MISSING, VerificationStatus.CONFIG_INVALID)) return AgentRoundsOutcome(validatedResult, outcome)
            lastValidatedResult = validatedResult
            lastVerificationResult = outcome
            if (round >= maximumRounds) return AgentRoundsOutcome(validatedResult, outcome)
            if (!Instant.now().plusSeconds(MINIMUM_REPAIR_SECONDS).isBefore(executionDeadline)) {
                return AgentRoundsOutcome(validatedResult, timeoutResult(outcome, round))
            }
            writeRepairInput(task, basePrompt, request!!.repairInstruction, outcome, round + 1, maximumRounds)
            client.progress(claim, "REPAIRING", (55 + round * 5).coerceAtMost(80), "Verification failed; starting repair round ${round + 1} of $maximumRounds.")
            round++
        }
    }

    private fun executeVerification(
        claim: ClaimedJob,
        workspace: Path,
        task: Path,
        repositoryState: V2RepositoryState,
        configFile: RepositoryVerificationConfig,
        changedPaths: Set<String>?,
        agentRound: Int,
        executionDeadline: Instant,
    ): VerificationResult {
        client.progress(claim, "VERIFYING", (55 + agentRound * 4).coerceAtMost(80), "Running repository verification after agent round $agentRound.")
        val selected = verification.selectedCommands(configFile, changedPaths).toSet()
        val results = configFile.commands.mapIndexed { index, command ->
            if (command !in selected) {
                VerificationCommandResult(command.id, command.argv, VerificationCommandStatus.SKIPPED, null, 0, null)
            } else {
                runVerificationCommand(claim, workspace, task, command, agentRound, index, executionDeadline)
            }
        }
        repositories.verifyAgentDidNotMutate(repositoryState, workspace)
        val status = when {
            results.any { it.status == VerificationCommandStatus.TIMEOUT } -> VerificationStatus.TIMEOUT
            results.any { it.status == VerificationCommandStatus.FAILED } -> VerificationStatus.FAILED
            results.all { it.status == VerificationCommandStatus.SKIPPED } -> VerificationStatus.SKIPPED
            else -> VerificationStatus.PASSED
        }
        return VerificationResult(status, configFile.version, agentRound, results)
    }

    private fun runVerificationCommand(
        claim: ClaimedJob,
        workspace: Path,
        task: Path,
        commandSpec: RepositoryVerificationCommand,
        agentRound: Int,
        commandIndex: Int,
        executionDeadline: Instant,
    ): VerificationCommandResult {
        val remainingMillis = Duration.between(Instant.now(), executionDeadline).toMillis()
        if (remainingMillis <= 0) return VerificationCommandResult(commandSpec.id, commandSpec.argv, VerificationCommandStatus.TIMEOUT, null, 0, "The hard job deadline left no time for this command.")
        val timeoutMillis = minOf(commandSpec.timeoutSeconds * 1000, remainingMillis)
        val workingDirectory = verification.commandWorkingDirectory(workspace, commandSpec)
        val relativeWorkingDirectory = workspace.relativize(workingDirectory).toString().replace('\\', '/')
        val containerWorkingDirectory = if (relativeWorkingDirectory.isBlank()) "/work" else "/work/$relativeWorkingDirectory"
        val name = "ar-v2-verify-${claim.job.id.take(8)}-${agentRound}-${commandIndex}"
        val docker = mutableListOf(
            "docker", "run", "--pull", "always", "--rm", "--name", name,
            "--memory", "8g", "--cpus", "4", "--pids-limit", "1024",
            "-v", "$workspace:/work", "-v", "${workspace.resolve(".git")}:/work/.git:ro",
            "-v", "${task.resolve("secrets")}:/job/secrets:ro", "-e", "GIT_OPTIONAL_LOCKS=0",
            "-w", containerWorkingDirectory,
        )
        val selected = claim.request.environmentKeys.associateWith { key ->
            config.projectCredentials[key] ?: throw JobFailure("REQUIRED_ENVIRONMENT_KEY_UNAVAILABLE", "Required environment key is unavailable on this worker.", false)
        }
        selected.keys.forEach { docker += listOf("-e", it) }
        docker += listOf("--entrypoint", commandSpec.argv.first(), config.executionImage)
        docker += commandSpec.argv.drop(1)
        val started = Instant.now()
        val processBuilder = ProcessBuilder(docker).redirectErrorStream(true)
        processBuilder.environment().putAll(selected)
        val process = processBuilder.start()
        val tail = OutputTail(20_000)
        val reader = Thread { process.inputStream.bufferedReader().use { input ->
            val buffer = CharArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                tail.append(buffer, count)
            }
        } }.apply { start() }
        var timedOut = false
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            val heartbeat = client.heartbeat(claim)
            if (!heartbeat.accepted || heartbeat.fenced || heartbeat.cancelRequested) {
                stopContainer(name, process)
                throw JobFailure(if (heartbeat.cancelRequested) "CANCELLED" else "ATTEMPT_FENCED", "Verification was stopped.", false)
            }
            if (Duration.between(started, Instant.now()).toMillis() >= timeoutMillis) {
                timedOut = true
                stopContainer(name, process)
                break
            }
        }
        reader.join(5_000)
        val duration = Duration.between(started, Instant.now()).toMillis().coerceAtLeast(0)
        val cleaned = redact(tail.value(), 20_000).takeLast(20_000).ifBlank { null }
        val exitCode = if (timedOut) null else process.exitValue()
        val status = when { timedOut -> VerificationCommandStatus.TIMEOUT; exitCode == 0 -> VerificationCommandStatus.PASSED; else -> VerificationCommandStatus.FAILED }
        client.log(claim, LogKind.SYSTEM, "Verification command ${commandSpec.id} finished with $status after ${duration}ms.")
        return VerificationCommandResult(commandSpec.id, commandSpec.argv, status, exitCode, duration, cleaned)
    }

    private fun timeoutResult(previous: VerificationResult?, agentRound: Int) = VerificationResult(
        VerificationStatus.TIMEOUT,
        previous?.configVersion,
        agentRound,
        previous?.commands.orEmpty(),
    )

    private fun writeRepairInput(task: Path, basePrompt: String, repairInstruction: String?, result: VerificationResult, nextRound: Int, maximumRounds: Int) {
        val failure = buildString {
            appendLine(repairInstruction?.trim()?.takeIf(String::isNotBlank) ?: "Repair the repository so that every configured verification command passes.")
            appendLine()
            appendLine("This is agent round $nextRound of $maximumRounds. Do not perform Git operations.")
            result.commands.filter { it.status in setOf(VerificationCommandStatus.FAILED, VerificationCommandStatus.TIMEOUT) }.forEach { command ->
                appendLine()
                appendLine("## ${command.id}")
                appendLine("argv: ${command.argv.joinToString(" ")}")
                appendLine("status: ${command.status}")
                appendLine("exitCode: ${command.exitCode ?: "none"}")
                command.outputTail?.let { appendLine("output tail:\n$it") }
            }
        }
        task.resolve("input/verification-failure.md").writeText(redact(failure, 60_000).take(60_000))
        task.resolve("input/prompt.md").writeText(
            "$basePrompt\n\nRepository verification failed. Read /job/input/verification-failure.md, repair the existing worktree, rerun relevant tests yourself, and return a fresh complete JSON result.",
        )
    }

    private fun readResult(claim: ClaimedJob, task: Path): JsonNode {
        val resultPath = task.resolve("output/result.json")
        if (!resultPath.isRegularFile() || resultPath.fileSize() > 1024L * 1024) throw JobFailure("RESULT_TOO_LARGE", "Provider did not produce a bounded result.json.", true)
        val result = runCatching { mapper.readTree(resultPath.toFile()) }.getOrElse { throw JobFailure("MODEL_OUTPUT_NOT_JSON", "Provider result is not valid JSON.", true) }
        if (SecretRedactor.contains(result.toString(), selectedSensitiveValues(claim.request.environmentKeys, config.projectCredentials))) {
            throw JobFailure("SECRET_EXPOSURE_BLOCKED", "Provider result contained a selected sensitive value.", false)
        }
        return result
    }

    private fun stopContainer(name: String, process: Process) {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        runCatching {
            ProcessBuilder(listOf("docker", "rm", "-f", name)).redirectErrorStream(true).start().apply {
                inputStream.readAllBytes()
                waitFor(15, TimeUnit.SECONDS)
            }
        }
    }

    private class OutputTail(private val maximum: Int) {
        private val text = StringBuilder()
        @Synchronized fun append(buffer: CharArray, count: Int) {
            text.append(buffer, 0, count)
            if (text.length > maximum) text.delete(0, text.length - maximum)
        }
        @Synchronized fun value(): String = text.toString()
    }

    companion object {
        private const val MINIMUM_REPAIR_SECONDS = 30L
        private const val FINALIZATION_RESERVE_SECONDS = 300L
    }

    private fun reconcilePublication(claim: ClaimedJob, root: Path): Boolean {
        val intent = claim.repositoryPublication ?: return false
        val checkout = claim.request.repositoryCheckout
            ?: throw JobFailure("REPOSITORY_PUBLICATION_AMBIGUOUS", "Publication intent exists without repository checkout.", false)
        val workspace = root.resolve("reconcile-workspace").also(Path::createDirectories)
        val state = repositories.checkout(checkout, workspace, root).copy(checkoutCommitSha = intent.checkoutCommitSha)
        return when (repositories.remotePublicationState(state, workspace, intent.intendedCommitSha, claim.job.id)) {
            RemotePublicationState.PRESENT -> {
                client.confirmPublication(claim, intent.intendedCommitSha)
                true
            }
            RemotePublicationState.ABSENT -> {
                client.discardPublication(claim, intent.intendedCommitSha)
                if (claim.attempt.number > claim.job.maxAttempts) {
                    throw JobFailure("GIT_PUSH_FAILED", "The prepared commit is not remote and normal technical attempts are exhausted.", false)
                }
                false
            }
            RemotePublicationState.AMBIGUOUS -> throw JobFailure("REPOSITORY_PUBLICATION_AMBIGUOUS", "Prepared commit cannot be safely reconciled with the remote story branch.", false)
        }
    }

    private fun prepare(claim: ClaimedJob, workspace: Path, task: Path): V2RepositoryState? {
        val input = task.resolve("input").also(Path::createDirectories)
        task.resolve("output/artifacts").createDirectories()
        val secrets = task.resolve("secrets").also(Path::createDirectories)
        task.resolve("docs").createDirectories()
        claim.request.input.objects.forEach { ref ->
            val dir = input.resolve("objects").resolve(ref.name).also(Path::createDirectories)
            client.download(claim, ref.objectId, dir.resolve("content"))
        }
        val selected = claim.request.environmentKeys.associateWith { key ->
            config.projectCredentials[key] ?: throw JobFailure("REQUIRED_ENVIRONMENT_KEY_UNAVAILABLE", "Required environment key is unavailable on this worker.", false)
        }
        val secretFile = secrets.resolve("secrets.env")
        secretFile.writeText(selected.entries.joinToString("\n", postfix = if (selected.isEmpty()) "" else "\n") { (key, value) -> "$key=${dotenvValue(value)}" })
        runCatching { Files.setPosixFilePermissions(secretFile, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }

        val artifactInstructions = claim.request.output.artifacts.joinToString("\n") {
            "- ${it.name}: write a ${it.mimeTypes.joinToString(" or ")} file to /job/output/artifacts/${it.name}; required=${it.required}; maxBytes=${it.maxBytes ?: "runtime default"}"
        }
        input.resolve("prompt.md").writeText(
            """${claim.request.input.instruction.trim()}

Read uploaded inputs below /job/input/objects and tool documentation below /job/docs. Return the bounded JSON result as your final response. Do not put large content in JSON.
Never display or copy values from /job/secrets/secrets.env into a provider request, transcript, result or artifact.
${if (claim.request.repositoryCheckout != null) "Work only in /work. Do not checkout, branch, commit, push, merge, create a pull request, modify Git configuration or inspect credentials. The Agent Runtime worker owns all Git metadata and publication." else ""}
${if (artifactInstructions.isBlank()) "No output artifacts are declared." else "Declared artifacts:\n$artifactInstructions"}
""".trimIndent(),
        )
        input.resolve("response-schema.json").writeText(claim.request.output.resultSchema?.toString() ?: "{}")
        task.resolve("docs/available-tools.md").writeText("The execution image contains build and test tools. Tool availability does not grant additional authority.\n")

        return claim.request.repositoryCheckout?.let { repositories.checkout(it, workspace, rootFor(workspace)) }
            ?: claim.request.repositorySnapshot?.let { snapshot ->
                repositories.execute(listOf("git", "clone", "--filter=blob:none", "--no-checkout", "--", snapshot.url, workspace.toString()), rootFor(workspace), 300, "GIT_CLONE_FAILED", true)
                repositories.execute(listOf("git", "checkout", "--detach", snapshot.commitSha), workspace, 120, "GIT_CLONE_FAILED", true)
                runCatching { repositories.execute(listOf("git", "remote", "remove", "origin"), workspace, 30) }
                null
            }
    }

    private fun complete(claim: ClaimedJob, workspace: Path, state: V2RepositoryState?, result: JsonNode, outputIds: Set<String>, verificationResult: VerificationResult?) {
        val checkout = claim.request.repositoryCheckout
        if (checkout == null) {
            client.submit(claim, result, outputIds, null, verificationResult)
            return
        }
        val captured = state ?: throw JobFailure("INVALID_REPOSITORY_CHECKOUT", "Repository checkout state is unavailable.", false)
        if (verificationResult?.status in setOf(VerificationStatus.FAILED, VerificationStatus.CONFIG_MISSING, VerificationStatus.CONFIG_INVALID, VerificationStatus.TIMEOUT)) {
            client.submit(claim, result, outputIds, null, verificationResult)
            return
        }
        when (checkout.publicationMode) {
            RepositoryPublicationMode.NONE -> client.submit(
                claim, result, outputIds,
                RepositoryResult(checkout.alias, checkout.branch, captured.checkoutCommitSha, RepositoryPublicationStatus.NONE),
                verificationResult,
            )
            RepositoryPublicationMode.COMMIT_AND_PUSH -> {
                val repositoryResult = repositories.prepareCommit(
                    checkout, captured, workspace, claim.job.id,
                    selectedSensitiveValues(claim.request.environmentKeys, config.projectCredentials),
                )
                if (repositoryResult.publicationStatus == RepositoryPublicationStatus.NO_CHANGES) {
                    client.submit(claim, result, outputIds, repositoryResult, verificationResult)
                    return
                }
                val intendedCommitSha = requireNotNull(repositoryResult.commitSha)
                client.preparePublication(claim, result, outputIds, repositoryResult, verificationResult)
                try {
                    repositories.push(checkout, captured, workspace)
                } catch (failure: JobFailure) {
                    if (failure.code == "BRANCH_CHANGED") client.discardPublication(claim, intendedCommitSha)
                    else if (failure.code == "GIT_PUSH_FAILED" && repositories.remotePublicationState(captured, workspace, intendedCommitSha, claim.job.id) == RemotePublicationState.PRESENT) {
                        client.confirmPublication(claim, intendedCommitSha)
                        return
                    }
                    throw failure
                }
                client.confirmPublication(claim, intendedCommitSha)
            }
        }
    }

    private fun runContainer(claim: ClaimedJob, workspace: Path, task: Path, agentRound: Int, executionDeadline: Instant): ContainerRunResult {
        val provider = claim.job.execution.vendorId
        val engine = if (provider == "openai") "CODEX" else "CLAUDE"
        val credentials = if (engine == "CODEX") config.codexCredentials else config.claudeCredentials
        if (engine == "CODEX" && credentials == null) throw JobFailure("PROVIDER_UNAVAILABLE", "Codex credentials are unavailable.", true)
        if (engine == "CLAUDE" && credentials == null && config.claudeOauthToken.isNullOrBlank()) throw JobFailure("PROVIDER_UNAVAILABLE", "Claude credentials are unavailable.", true)
        val name = "ar-v2-${claim.job.id.take(8)}-${claim.attempt.id.take(8)}-$agentRound"
        val command = mutableListOf(
            "docker", "run", "--pull", "always", "--rm", "--name", name, "--memory", "8g", "--cpus", "4", "--pids-limit", "1024",
            "-v", "$workspace:/work", "-v", "${task.resolve("input")}:/job/input:ro", "-v", "${task.resolve("secrets")}:/job/secrets:ro",
            "-v", "${task.resolve("docs")}:/job/docs:ro", "-v", "${task.resolve("output")}:/job/output",
        )
        if (claim.request.repositoryCheckout != null) command += listOf("-v", "${workspace.resolve(".git")}:/work/.git:ro", "-e", "GIT_OPTIONAL_LOCKS=0")
        credentials?.let { command += listOf("-v", "${it.toAbsolutePath()}:/credential-source:ro") }
        if (engine == "CLAUDE" && !config.claudeOauthToken.isNullOrBlank()) command += listOf("-e", "CLAUDE_CODE_OAUTH_TOKEN")
        command += listOf("-e", "AR_ENGINE=$engine", "-e", "AR_MODEL=${claim.job.execution.model}", "-e", "AR_JOB_KIND=${claim.job.jobKind.name}", "-e", "AR_OUTPUT_ATTEMPT=$agentRound", "-e", "AR_RESULT_FILE=/job/output/result.json", config.executionImage)
        val process = ProcessBuilder(command).redirectErrorStream(true).also {
            if (engine == "CLAUDE" && !config.claudeOauthToken.isNullOrBlank()) it.environment()["CLAUDE_CODE_OAUTH_TOKEN"] = config.claudeOauthToken
        }.start()
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val cleaned = redact(line, 8192)
            if (cleaned.isNotBlank()) runCatching { client.log(claim, LogKind.AGENT_TEXT, cleaned) }
        } } }.apply { start() }
        var timedOut = false
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            val heartbeat = client.heartbeat(claim)
            if (!heartbeat.accepted || heartbeat.fenced || heartbeat.cancelRequested || !Instant.now().isBefore(executionDeadline)) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                if (heartbeat.cancelRequested || !heartbeat.accepted || heartbeat.fenced) {
                    throw JobFailure(if (heartbeat.cancelRequested) "CANCELLED" else "ATTEMPT_FENCED", "Execution was stopped.", false)
                }
                timedOut = true
                break
            }
        }
        reader.join(5000)
        return ContainerRunResult(if (timedOut) 124 else process.exitValue(), timedOut)
    }

    private fun uploadOutputs(claim: ClaimedJob, task: Path): Set<String> = claim.request.output.artifacts.mapNotNull { declaration ->
        val path = task.resolve("output/artifacts/${declaration.name}")
        if (!path.isRegularFile()) {
            if (declaration.required) throw JobFailure("MISSING_REQUIRED_ARTIFACT", "Required artifact ${declaration.name} is missing.", true)
            return@mapNotNull null
        }
        if (path.isSymbolicLink()) throw JobFailure("UNSAFE_ARTIFACT", "Artifact may not be a symbolic link.", false)
        val size = path.fileSize()
        declaration.maxBytes?.let { if (size > it) throw JobFailure("OUTPUT_TOO_LARGE", "Artifact ${declaration.name} is too large.", false) }
        val bytes = path.readBytes()
        if (SecretRedactor.contains(bytes, selectedSensitiveValues(claim.request.environmentKeys, config.projectCredentials))) throw JobFailure("SECRET_EXPOSURE_BLOCKED", "Artifact contained a selected sensitive value.", false)
        val mime = Files.probeContentType(path) ?: declaration.mimeTypes.first()
        if (mime !in declaration.mimeTypes) throw JobFailure("OUTPUT_MIME_NOT_ALLOWED", "Artifact ${declaration.name} has MIME $mime.", false)
        client.upload(claim, declaration.name, path, mime)
    }.toSet()

    private fun dotenvValue(value: String): String {
        if ('\n' in value || '\r' in value || '\u0000' in value) throw JobFailure("INVALID_PROJECT_CREDENTIAL", "Project credential contains unsupported control characters.", false)
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun rootFor(workspace: Path): Path = workspace.parent ?: config.workRoot
}

class V2RuntimeClient(private val config: WorkerConfig, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    fun register(body: WorkerRegistrationRequest) = post("/v2/workers/register", body, WorkerView::class.java)!!
    fun claim(body: ClaimRequest) = post("/v2/workers/${config.workerId}/claims", body, ClaimedJob::class.java)
    fun heartbeat(claim: ClaimedJob) = post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/heartbeat", AttemptAuth(claim.attempt.id, claim.fencingToken), HeartbeatResponse::class.java)!!
    fun progress(claim: ClaimedJob, phase: String, percent: Int?, message: String?) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/progress", ProgressRequest(claim.attempt.id, claim.fencingToken, phase, percent, message), Void::class.java) }
    fun log(claim: ClaimedJob, kind: LogKind, text: String) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/logs", AppendLogRequest(claim.fencingToken, UUID.randomUUID().toString(), kind, text.take(8192), observedAt = Instant.now()), Void::class.java) }
    fun audioUsage(claim: ClaimedJob, seconds: Long) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/usage-events", AppendUsageRequest(claim.fencingToken, UUID.randomUUID().toString(), Instant.now(), listOf(UsageMetricValue(UsageMetric.AUDIO_INPUT_SECONDS, seconds.toString(), UsageUnit.SECOND)), source = UsageSource.WORKER_MEASURED), Void::class.java) }
    fun measuredUsage(claim: ClaimedJob, inputTokens: Long, outputTokens: Long) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/usage-events", AppendUsageRequest(claim.fencingToken, UUID.randomUUID().toString(), Instant.now(), listOf(UsageMetricValue(UsageMetric.INPUT_TOKENS, inputTokens.toString(), UsageUnit.TOKEN), UsageMetricValue(UsageMetric.OUTPUT_TOKENS, outputTokens.toString(), UsageUnit.TOKEN)), source = UsageSource.WORKER_MEASURED), Void::class.java) }
    fun submit(claim: ClaimedJob, result: JsonNode, objectIds: Set<String>, repositoryResult: RepositoryResult?, verificationResult: VerificationResult?) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/result", SubmitResultRequest(claim.fencingToken, result, objectIds, repositoryResult, verificationResult), Void::class.java) }
    fun preparePublication(claim: ClaimedJob, result: JsonNode, objectIds: Set<String>, repositoryResult: RepositoryResult, verificationResult: VerificationResult?) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/repository-publication", PrepareRepositoryPublicationRequest(claim.fencingToken, result, objectIds, repositoryResult, verificationResult), RepositoryPublicationIntentView::class.java) }
    fun confirmPublication(claim: ClaimedJob, commitSha: String) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/repository-publication/confirm", ConfirmRepositoryPublicationRequest(claim.fencingToken, commitSha), Void::class.java) }
    fun discardPublication(claim: ClaimedJob, commitSha: String) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/repository-publication/discard", DiscardRepositoryPublicationRequest(claim.fencingToken, commitSha), Void::class.java) }
    fun fail(claim: ClaimedJob, code: String, message: String, retryable: Boolean) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/fail", FailAttemptRequest(claim.fencingToken, code, message, retryable), Void::class.java) }

    fun download(claim: ClaimedJob, objectId: String, target: Path) {
        val request = HttpRequest.newBuilder(uri("/v2/workers/${config.workerId}/jobs/${claim.job.id}/objects/$objectId/content")).timeout(Duration.ofMinutes(30)).header("Authorization", "Bearer ${config.token}").header("X-Attempt-Id", claim.attempt.id).header("X-Fencing-Token", claim.fencingToken).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IOException("Input download returned HTTP ${response.statusCode()}")
        response.body().use { input -> Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use(input::transferTo) }
    }

    fun upload(claim: ClaimedJob, name: String, path: Path, mime: String): String {
        val sha = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            HexFormat.of().formatHex(digest.digest())
        }
        val reservation = post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/output-objects", CreateOutputUploadRequest(claim.attempt.id, claim.fencingToken, name, path.fileName.toString(), mime, path.fileSize(), sha), UploadView::class.java)!!
        var offset = reservation.offset
        while (offset < path.fileSize()) {
            val current = offset
            val publisher = HttpRequest.BodyPublishers.ofInputStream { Files.newInputStream(path).also { it.skipNBytes(current) } }
            val request = HttpRequest.newBuilder(uri("/v2/workers/${config.workerId}/jobs/${claim.job.id}/output-objects/${reservation.uploadId}")).timeout(Duration.ofMinutes(30)).header("Authorization", "Bearer ${config.token}").header("X-Attempt-Id", claim.attempt.id).header("X-Fencing-Token", claim.fencingToken).header("Upload-Offset", current.toString()).header("Content-Type", "application/offset+octet-stream").method("PATCH", publisher).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 204) throw IOException("Output upload returned HTTP ${response.statusCode()}: ${safe(response.body())}")
            offset = response.headers().firstValue("Upload-Offset").orElseThrow().toLong()
        }
        val complete = post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/output-objects/${reservation.uploadId}/complete", AttemptAuth(claim.attempt.id, claim.fencingToken), JsonNode::class.java)!!
        return complete.path("objectId").asText()
    }

    private fun <T> post(path: String, body: Any, type: Class<T>): T? {
        val request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(35)).header("Authorization", "Bearer ${config.token}").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 204) return null
        if (response.statusCode() !in 200..299) throw IOException("Runtime returned HTTP ${response.statusCode()}: ${safe(response.body())}")
        return mapper.readValue(response.body(), type)
    }

    private fun uri(path: String) = URI.create(config.serverUrl + path)
}
