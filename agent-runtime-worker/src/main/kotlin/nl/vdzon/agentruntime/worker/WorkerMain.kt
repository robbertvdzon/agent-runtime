package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.agentruntime.contracts.*
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

data class WorkerConfig(
    val serverUrl: String,
    val token: String,
    val workerId: String,
    val workRoot: Path,
    val executionImage: String,
    val codexCredentials: Path?,
    val claudeCredentials: Path?,
    val repositoryAliases: Map<String, String>,
    val projectCredentialsPath: Path,
    val projectCredentials: MutableMap<String, String>,
) {
    companion object {
        fun load(): WorkerConfig {
            val root = Path.of(System.getProperty("user.dir"))
            val values = EnvFiles.load(root)
            val projectCredentials = ProjectCredentials.load(root.resolve("project-credentials.env"))
            fun required(name: String) = values[name]?.takeIf(String::isNotBlank) ?: error("Missing required $name")
            val aliases = values.filterKeys { it.startsWith("AR_REPOSITORY_") && it.endsWith("_URL") }
                .mapKeys { (key, _) -> key.removePrefix("AR_REPOSITORY_").removeSuffix("_URL").lowercase().replace('_', '-') }
            return WorkerConfig(
                required("AR_SERVER_URL").removeSuffix("/"), required("AR_WORKER_TOKEN"),
                values["AR_WORKER_ID"]?.takeIf(String::isNotBlank) ?: InetAddress.getLocalHost().hostName,
                Path.of(values["AR_WORK_ROOT"] ?: "work/worker").toAbsolutePath().normalize(),
                values["AR_EXECUTION_IMAGE"] ?: "ghcr.io/robbertvdzon/agent-runtime-execution:main",
                values["AR_CODEX_CREDENTIALS_DIR"]?.takeIf(String::isNotBlank)?.let(Path::of),
                values["AR_CLAUDE_CREDENTIALS_DIR"]?.takeIf(String::isNotBlank)?.let(Path::of),
                aliases, root.resolve("project-credentials.env"), projectCredentials.toMutableMap(),
            )
        }
    }
}

fun main(args: Array<String>) {
    val config = WorkerConfig.load()
    SecretRedactor.configure(config.projectCredentials.values)
    config.workRoot.createDirectories()
    val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    val client = RuntimeClient(config, mapper)
    val bootId = UUID.randomUUID().toString()
    val providers = buildSet {
        if (config.codexCredentials?.exists() == true) add(Provider.CODEX)
        if (config.claudeCredentials?.exists() == true) add(Provider.CLAUDE)
    }
    require(providers.isNotEmpty()) { "No provider credential directory configured." }
    val capabilities = buildSet { add("application-work"); if (config.repositoryAliases.isNotEmpty()) add("repository-work") }
    fun register() = client.register(WorkerRegistrationRequest(
        config.workerId, bootId, capabilities, providers, emptySet(), config.projectCredentials.keys, 1, mapOf("worker" to "0.1.0"),
    ))
    register()
    println("Agent Runtime worker ${config.workerId} online with ${providers.joinToString()}.")
    val journal = WorkerJournal(config.workRoot, mapper)
    cleanupOrphanAttempts(config.workRoot, journal.entries().map { it.claim.job.id }.toSet())
    val executor = JobExecutor(config, client, mapper, bootId, journal)
    executor.recoverBeforeClaiming()
    while (!Thread.currentThread().isInterrupted) {
        try {
            val refreshed = try { ProjectCredentials.load(config.projectCredentialsPath) } catch (error: Exception) {
                val hadKeys = config.projectCredentials.isNotEmpty()
                config.projectCredentials.clear()
                SecretRedactor.configure(emptyList())
                if (hadKeys) register()
                throw error
            }
            if (refreshed != config.projectCredentials) {
                val keysChanged = refreshed.keys != config.projectCredentials.keys
                config.projectCredentials.clear(); config.projectCredentials.putAll(refreshed)
                SecretRedactor.configure(refreshed.values)
                if (keysChanged) register()
            }
            val claimed = client.claim(ClaimRequest(bootId, capabilities, providers, emptySet(), 20))
            if (claimed != null) executor.execute(claimed)
        } catch (error: Exception) {
            System.err.println("Worker loop temporarily unavailable: ${safe(error.message)}")
            Thread.sleep(5_000)
        }
    }
}

class JobExecutor(
    private val config: WorkerConfig,
    private val client: RuntimeClient,
    private val mapper: ObjectMapper,
    private val bootId: String,
    private val journal: WorkerJournal,
) {
    fun recoverBeforeClaiming() {
        journal.entries().forEach { entry ->
            val claim = entry.claim
            val jobRoot = config.workRoot.resolve(claim.job.id)
            val workspace = jobRoot.resolve("workspace")
            val taskRoot = jobRoot.resolve("job")
            try {
                if (!Instant.now().isBefore(claim.attemptDeadline)) throw JobFailure("EXECUTION_TIMEOUT", "Recovered attempt deadline has expired.", true)
                if (claim.job.jobKind == JobKind.APPLICATION_WORK) {
                    stopContainers(claim)
                    throw JobFailure("OUTPUT_ATTEMPTS_INTERRUPTED", "Application output was interrupted by a worker restart.", true)
                }
                val heartbeat = client.heartbeat(claim, bootId)
                if (!heartbeat.accepted || heartbeat.fenced) {
                    stopContainer(containerName(claim))
                    return@forEach
                }
                val exit = waitForRecoveredContainer(claim)
                if (heartbeat.cancelRequested) throw JobFailure("CANCELLED", "Recovered execution was cancelled.", false)
                if (exit != null && exit != 0) throw JobFailure("ENGINE_FAILED", "Recovered provider process exited with code $exit.", true)
                val result = publishRepository(claim, workspace)
                uploadArtifacts(claim, taskRoot)
                client.complete(claim, result)
            } catch (failure: JobFailure) {
                runCatching { client.fail(claim, failure.code, failure.message.orEmpty(), failure.retryable) }
            } catch (error: Exception) {
                runCatching { client.fail(claim, "RECOVERY_FAILED", safe(error.message), true) }
            } finally {
                journal.remove(claim.job.id)
                runCatching { deleteTree(jobRoot) }
            }
        }
    }

    fun execute(claim: ClaimedJob) {
        val jobRoot = config.workRoot.resolve(claim.job.id).also { it.createDirectories() }
        val workspace = jobRoot.resolve("workspace").also { it.createDirectories() }
        val taskRoot = jobRoot.resolve("job")
        try {
            client.progress(claim, "PREPARING", 5, "Preparing isolated workspace.")
            when (claim.job.jobKind) {
                JobKind.APPLICATION_WORK -> prepareApplication(claim, workspace)
                JobKind.REPOSITORY_WORK -> prepareRepository(claim, workspace)
            }
            prepareTaskDirectory(claim, taskRoot)
            journal.save(JournalEntry(claim))
            if (claim.job.jobKind == JobKind.APPLICATION_WORK) {
                executeApplication(claim, workspace, taskRoot)
            } else {
                client.transcript(claim, transcriptSequence(claim, 1), TranscriptKind.PROMPT, prompt(claim))
                client.progress(claim, "EXECUTING", 15, "Starting ${claim.job.provider} in the execution container.")
                val exit = runContainer(claim, workspace, taskRoot, 1, taskRoot.resolve("output/result.json")) { heartbeatRequestsStop(claim) }
                if (exit != 0) throw JobFailure("ENGINE_FAILED", "Provider process exited with code $exit.", exit in setOf(124, 137))
                taskRoot.resolve("output/result.json").takeIf(Path::exists)?.let {
                    client.transcript(claim, transcriptSequence(claim, 2), TranscriptKind.PROVIDER_RESULT, it.readText().take(100_000))
                }
                client.progress(claim, "VALIDATING", 85, "Validating bounded result and workspace.")
                val result = publishRepository(claim, workspace)
                uploadArtifacts(claim, taskRoot)
                client.complete(claim, result)
            }
        } catch (failure: JobFailure) {
            client.fail(claim, failure.code, failure.message.orEmpty(), failure.retryable)
        } catch (error: Exception) {
            client.fail(claim, "WORKER_ERROR", safe(error.message), true)
        } finally {
            journal.remove(claim.job.id)
            runCatching { deleteTree(jobRoot) }
        }
    }

    private fun executeApplication(claim: ClaimedJob, workspace: Path, taskRoot: Path) {
        var correctionErrors = emptyList<JsonValidationError>()
        var invocation = 1
        var transcriptOffset = 1L
        client.transcript(claim, transcriptSequence(claim, transcriptOffset++), TranscriptKind.PROMPT, prompt(claim))
        while (true) {
            val output = client.startOutputAttempt(claim, "${claim.attemptId}-output-$invocation")
            val promptFile = taskRoot.resolve("input/prompt.md")
            setPermissions(promptFile, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            promptFile.writeText(if (output.outputAttemptNumber == 1) prompt(claim) else correctionPrompt(claim, output.correctionErrors))
            if (output.outputAttemptNumber > 1) {
                client.transcript(claim, transcriptSequence(claim, transcriptOffset++), TranscriptKind.CORRECTION, correctionPrompt(claim, output.correctionErrors))
            }
            setPermissions(promptFile, setOf(PosixFilePermission.OWNER_READ))
            val resultPath = taskRoot.resolve("output/result-${output.outputAttemptNumber}.txt")
            resultPath.deleteIfExists()
            client.progress(claim, "EXECUTING", 15 + output.outputAttemptNumber * 20, "Starting output attempt ${output.outputAttemptNumber} of ${output.maxOutputAttempts}.")
            val exit = runContainer(claim, workspace, taskRoot, output.outputAttemptNumber, resultPath) { heartbeatRequestsStop(claim) }
            if (exit != 0) throw JobFailure("ENGINE_FAILED", "Provider process exited with code $exit.", exit in setOf(124, 137))
            if (!resultPath.exists() || resultPath.fileSize() > 5L * 1024 * 1024) throw JobFailure("RESULT_TOO_LARGE", "Provider did not produce a bounded candidate result.", false)
            val candidate = providerAdapter(claim.job.provider).candidate(resultPath)
            if (SecretRedactor.contains(candidate)) throw JobFailure("SECRET_EXPOSURE_BLOCKED", "Provider output contained a locally known project credential value.", false)
            client.transcript(claim, transcriptSequence(claim, transcriptOffset++), TranscriptKind.PROVIDER_RESULT, candidate.take(100_000))
            val response = client.submitOutputCandidate(claim, output.outputAttemptId, candidate)
            when (response.status) {
                OutputCandidateStatus.ACCEPTED -> {
                    uploadArtifacts(claim, taskRoot)
                    client.finalizeOutput(claim, output.outputAttemptId)
                    return
                }
                OutputCandidateStatus.CORRECTION_REQUIRED -> correctionErrors = response.validationErrors
                OutputCandidateStatus.EXHAUSTED -> return
            }
            invocation++
        }
    }

    private fun heartbeatRequestsStop(claim: ClaimedJob): Boolean {
        val heartbeat = client.heartbeat(claim, bootId)
        return heartbeat.cancelRequested || heartbeat.fenced || !heartbeat.accepted
    }

    private fun prepareApplication(claim: ClaimedJob, workspace: Path) {
        claim.request.repositorySnapshot?.let { snapshot ->
            command(listOf("git", "clone", "--filter=blob:none", "--no-checkout", snapshot.url, workspace.toString()), config.workRoot, 300)
            command(listOf("git", "checkout", "--detach", snapshot.commitSha), workspace, 120)
            command(listOf("git", "remote", "remove", "origin"), workspace, 30)
        }
    }

    private fun prepareRepository(claim: ClaimedJob, workspace: Path) {
        val request = claim.request.repositoryRequest ?: throw JobFailure("INVALID_REPOSITORY_REQUEST", "Repository request missing.", false)
        val url = config.repositoryAliases[request.alias] ?: throw JobFailure("UNKNOWN_REPOSITORY_ALIAS", "Worker does not know repository alias ${request.alias}.", false)
        command(listOf("git", "clone", url, workspace.toString()), config.workRoot, 300)
        command(listOf("git", "checkout", request.baseBranch), workspace, 60)
        command(listOf("git", "pull", "--ff-only"), workspace, 120)
        command(listOf("git", "checkout", "-b", branchName(claim)), workspace, 60)
    }

    private fun prepareTaskDirectory(claim: ClaimedJob, taskRoot: Path) {
        val input = taskRoot.resolve("input").also { it.createDirectories() }
        val attachmentDirectory = input.resolve("attachments").also { it.createDirectories() }
        val secrets = taskRoot.resolve("secrets").also { it.createDirectories() }
        val docs = taskRoot.resolve("docs").also { it.createDirectories() }
        taskRoot.resolve("output/artifacts").createDirectories()

        input.resolve("prompt.md").writeText(prompt(claim))
        claim.request.responseSchema?.let { input.resolve("response-schema.json").writeText(it.toPrettyString()) }
        var totalAttachmentBytes = 0L
        claim.request.attachments.forEach { attachment ->
            requireSafeFilename(attachment.filename)
            val content = runCatching { Base64.getDecoder().decode(attachment.contentBase64) }
                .getOrElse { throw JobFailure("ATTACHMENT_INVALID_BASE64", "Attachment Base64 is invalid.", false) }
            if (content.size > 2 * 1024 * 1024) throw JobFailure("ATTACHMENT_TOO_LARGE", "Attachment exceeds the worker limit.", false)
            totalAttachmentBytes += content.size
            if (totalAttachmentBytes > 10L * 1024 * 1024) throw JobFailure("JOB_ATTACHMENT_LIMIT", "Attachments exceed the worker job limit.", false)
            if (!matchesMime(attachment.mimeType, content)) throw JobFailure("ATTACHMENT_MIME_MISMATCH", "Attachment content does not match its declared MIME type.", false)
            attachmentDirectory.resolve(attachment.filename).writeBytes(content)
        }

        val selected = claim.request.environmentKeys.associateWith { key ->
            config.projectCredentials[key] ?: throw JobFailure("REQUIRED_ENVIRONMENT_KEY_UNAVAILABLE", "Required environment key is not locally available.", false)
        }
        val secretFile = secrets.resolve("secrets.env")
        secretFile.writeText(selected.entries.joinToString("\n", postfix = if (selected.isEmpty()) "" else "\n") { (key, value) -> "$key=${dotenvValue(value)}" })
        setPermissions(secretFile, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        docs.resolve("available-tools.md").writeText("""
            # Available tools

            The execution image contains Codex or Claude, Git, Java/Maven, Node, Playwright/Chromium,
            oc/kubectl, gh and PostgreSQL clients. Presence of a tool does not grant authority.

            Input is under `/job/input`, selected project values under `/job/secrets/secrets.env`,
            and writable output under `/job/output`. Never include secret values in a provider prompt,
            another AI request, transcript, result or artifact. Write the structured result to
            `/job/output/result.json` and evidence files directly to `/job/output/artifacts`.

            A selected key ending in `__OPENSHIFT_KUBECONFIG_BASE64` contains a Base64-encoded
            kubeconfig. Decode its value without printing it to a mode-0600 file under `/tmp`, set
            `KUBECONFIG` to that file, and delete it when the OpenShift operation is complete.
        """.trimIndent())
        listOf(input, docs).forEach(::makeReadOnlyTree)
    }

    private fun runContainer(claim: ClaimedJob, workspace: Path, taskRoot: Path, outputAttemptNumber: Int, resultPath: Path, heartbeat: () -> Boolean): Int {
        val credentials = providerAdapter(claim.job.provider).credentials()
        val name = containerName(claim, outputAttemptNumber)
        val command = mutableListOf(
            "docker", "run", "--pull", "always", "--rm", "--name", name,
            "--label", "nl.vdzon.agent-runtime.worker=${config.workerId}",
            "--label", "nl.vdzon.agent-runtime.boot=$bootId",
            "--label", "nl.vdzon.agent-runtime.job=${claim.job.id}",
            "--label", "nl.vdzon.agent-runtime.attempt=${claim.attemptId}",
            "--memory", "8g", "--cpus", "4", "--pids-limit", "1024",
            "-v", "${workspace}:/work",
            "-v", "${taskRoot.resolve("input")}:/job/input:ro",
            "-v", "${taskRoot.resolve("secrets")}:/job/secrets:ro",
            "-v", "${taskRoot.resolve("docs")}:/job/docs:ro",
            "-v", "${taskRoot.resolve("output")}:/job/output",
            "-v", "${credentials.toAbsolutePath()}:/credential-source:ro",
        )
        if (claim.job.provider == Provider.CLAUDE) {
            val claudeConfig = credentials.toAbsolutePath().parent.resolve(".claude.json")
            if (!claudeConfig.isSymbolicLink() && claudeConfig.isRegularFile()) {
                command += listOf("-v", "$claudeConfig:/credential-config.json:ro")
            }
        }
        command += listOf(
            "-e", "AR_ENGINE=${claim.job.provider.name}", "-e", "AR_MODEL=${claim.job.model}", "-e", "AR_JOB_KIND=${claim.job.jobKind.name}",
            "-e", "AR_OUTPUT_ATTEMPT=$outputAttemptNumber", "-e", "AR_RESULT_FILE=/job/output/${resultPath.fileName}",
            config.executionImage,
        )
        val process = ProcessBuilder(command).inheritIO().start()
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            val timedOut = !Instant.now().isBefore(claim.attemptDeadline)
            if (timedOut || heartbeat()) {
                process.descendants().forEach { it.destroy() }
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                if (timedOut) throw JobFailure("EXECUTION_TIMEOUT", "The hard execution deadline expired.", true)
                throw JobFailure("CANCELLED", "Execution stopped after cancellation or fencing.", false)
            }
        }
        return process.exitValue()
    }

    private fun uploadArtifacts(claim: ClaimedJob, taskRoot: Path) {
        val directory = taskRoot.resolve("output/artifacts")
        if (!directory.exists()) return
        val files = Files.list(directory).use { it.toList() }
        if (files.size > 25) throw JobFailure("JOB_ARTIFACT_LIMIT", "Too many output artifacts.", false)
        var total = 0L
        files.forEach { path ->
            if (path.isSymbolicLink() || !path.isRegularFile()) throw JobFailure("UNSAFE_ARTIFACT", "Artifacts must be direct regular files.", false)
            requireSafeFilename(path.fileName.toString())
            val size = path.fileSize()
            if (size > 5L * 1024 * 1024) throw JobFailure("ARTIFACT_TOO_LARGE", "Artifact exceeds 5 MB.", false)
            total += size
            if (total > 25L * 1024 * 1024) throw JobFailure("JOB_ARTIFACT_LIMIT", "Artifacts exceed 25 MB.", false)
            val content = path.readBytes()
            if (SecretRedactor.contains(content)) throw JobFailure("SECRET_EXPOSURE_BLOCKED", "An artifact contained a locally known project credential value.", false)
            val sha256 = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
            client.uploadArtifact(claim, path.fileName.toString(), detectMime(path), sha256, content)
        }
    }

    private fun detectMime(path: Path): String = Files.probeContentType(path) ?: when (path.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun matchesMime(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP"
        "application/pdf" -> bytes.size >= 5 && String(bytes, 0, 5) == "%PDF-"
        "text/plain", "application/json" -> true
        else -> false
    }

    private fun requireSafeFilename(filename: String) {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}").matches(filename) || filename in setOf(".", "..")) {
            throw JobFailure("UNSAFE_FILENAME", "Unsafe task filename.", false)
        }
    }

    private fun dotenvValue(value: String): String {
        if ('\n' in value || '\r' in value || '\u0000' in value) throw JobFailure("INVALID_PROJECT_CREDENTIAL", "Project credential contains unsupported control characters.", false)
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun makeReadOnlyTree(root: Path) {
        Files.walk(root).use { paths -> paths.forEach { path ->
            setPermissions(path, if (path.isDirectory()) setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE) else setOf(PosixFilePermission.OWNER_READ))
        } }
    }

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }

    private fun publishRepository(claim: ClaimedJob, workspace: Path): JsonNode {
        blockUnsafeFiles(workspace)
        val diff = command(listOf("git", "diff", "--stat"), workspace, 30).take(20_000)
        val changed = command(listOf("git", "status", "--porcelain"), workspace, 30)
        if (changed.isBlank()) throw JobFailure("NO_CHANGES", "Repository job produced no changes.", false)
        command(listOf("git", "add", "--all"), workspace, 30)
        command(listOf("git", "commit", "-m", "agent-runtime: job ${claim.job.id}"), workspace, 60)
        val sha = command(listOf("git", "rev-parse", "HEAD"), workspace, 30).trim()
        val request = claim.request.repositoryRequest!!
        var pullRequestUrl: String? = null
        if (request.publish) {
            command(listOf("git", "push", "-u", "origin", branchName(claim)), workspace, 180)
            pullRequestUrl = runCatching { command(listOf("gh", "pr", "create", "--fill", "--base", request.baseBranch, "--head", branchName(claim)), workspace, 120).trim() }.getOrNull()
        }
        return mapper.createObjectNode().apply {
            put("branch", branchName(claim)); put("commitSha", sha); put("diffStat", diff); put("published", request.publish)
            pullRequestUrl?.let { put("pullRequestUrl", it) }
        }
    }

    private fun blockUnsafeFiles(workspace: Path) {
        val changed = command(listOf("git", "status", "--porcelain"), workspace, 30).lineSequence()
            .filter(String::isNotBlank)
            .map { it.drop(3).substringAfter(" -> ") }
            .map { workspace.resolve(it).normalize() }
            .toList()
        changed.forEach { path ->
            if (!path.startsWith(workspace)) throw JobFailure("UNSAFE_PATH", "Repository change escapes the workspace.", false)
            if (path.isSymbolicLink()) throw JobFailure("UNSAFE_SYMLINK", "Repository changes contain a symbolic link.", false)
            if (path.isRegularFile() && path.fileSize() > 20L * 1024 * 1024) throw JobFailure("FILE_TOO_LARGE", "Repository changes contain a file over 20 MB.", false)
            if (path.fileName.toString() in setOf("secrets.env", ".env", "id_rsa")) throw JobFailure("SECRET_FILE_BLOCKED", "Repository changes contain a forbidden secret filename.", false)
        }
    }

    private fun prompt(claim: ClaimedJob): String = """
        ${claim.request.prompt.trim()}

        Read task input from /job/input and tool documentation from /job/docs/available-tools.md.
        Never copy values from /job/secrets/secrets.env into a provider request, transcript, result, or artifact.
        ${if (claim.job.jobKind == JobKind.APPLICATION_WORK) "Write only the complete JSON result to /job/output/result.json and satisfy /job/input/response-schema.json when it exists. Write evidence files directly to /job/output/artifacts." else "Make the requested changes in /work. Do not commit, push, create a pull request, or read credentials; the worker owns publication."}
    """.trimIndent()

    private fun providerAdapter(provider: Provider): ProviderAdapter = when (provider) {
        Provider.CODEX -> CodexProviderAdapter(config.codexCredentials)
        Provider.CLAUDE -> ClaudeProviderAdapter(config.claudeCredentials)
        Provider.MOCKED -> throw JobFailure("PROVIDER_UNAVAILABLE", "MOCKED never runs on a worker.", false)
    }

    private fun correctionPrompt(claim: ClaimedJob, errors: List<JsonValidationError>): String = """
        ${prompt(claim)}

        Your previous answer was rejected by the authoritative server validator. Return the complete
        answer again; do not return a patch and do not repeat the previous candidate. Correct these errors:
        ${errors.joinToString("\n") { "- ${it.path} [${it.keyword}]: ${it.message}" }}
    """.trimIndent()

    private fun branchName(claim: ClaimedJob): String = "agent-runtime/${claim.job.id}"

    private fun transcriptSequence(claim: ClaimedJob, offset: Long): Long = claim.job.attemptCount.toLong() * 1_000_000L + offset

    private fun containerName(claim: ClaimedJob, outputAttemptNumber: Int = 1): String = "ar-${claim.job.id.take(8)}-${claim.attemptId.take(8)}-o$outputAttemptNumber"

    private fun stopContainers(claim: ClaimedJob) {
        (1..3).forEach { stopContainer(containerName(claim, it)) }
    }

    private fun waitForRecoveredContainer(claim: ClaimedJob): Int? {
        val name = containerName(claim)
        val inspect = ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name).redirectErrorStream(true).start()
        val present = inspect.waitFor(10, TimeUnit.SECONDS) && inspect.exitValue() == 0
        if (!present) return null
        val running = inspect.inputStream.bufferedReader().readText().trim() == "true"
        if (!running) return command(listOf("docker", "inspect", "-f", "{{.State.ExitCode}}", name), config.workRoot, 10).trim().toIntOrNull()
        val wait = ProcessBuilder("docker", "wait", name).redirectErrorStream(true).start()
        while (!wait.waitFor(20, TimeUnit.SECONDS)) {
            if (!Instant.now().isBefore(claim.attemptDeadline)) {
                stopContainer(name)
                throw JobFailure("EXECUTION_TIMEOUT", "Recovered attempt deadline has expired.", true)
            }
            val heartbeat = client.heartbeat(claim, bootId)
            if (!heartbeat.accepted || heartbeat.fenced || heartbeat.cancelRequested) {
                stopContainer(name)
                throw JobFailure("CANCELLED", "Recovered container stopped after cancellation or fencing.", false)
            }
        }
        return wait.inputStream.bufferedReader().readText().trim().lineSequence().lastOrNull()?.toIntOrNull()
    }

    private fun stopContainer(name: String) {
        runCatching { ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS) }
    }

    private fun command(argv: List<String>, cwd: Path, timeoutSeconds: Long): String {
        val process = ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (output.length < 100_000) output.appendLine(it) } } }.apply { start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw JobFailure("COMMAND_TIMEOUT", "Controlled command timed out: ${argv.first()}", true) }
        reader.join(5_000)
        if (process.exitValue() != 0) throw JobFailure("COMMAND_FAILED", "Controlled command ${argv.first()} failed: ${safe(output.toString())}", true)
        return output.toString()
    }
}

interface ProviderAdapter {
    fun credentials(): Path
    fun candidate(resultPath: Path): String
}

class CodexProviderAdapter(private val credentialPath: Path?) : ProviderAdapter {
    override fun credentials(): Path = credentialPath
        ?: throw JobFailure("PROVIDER_UNAVAILABLE", "Credentials for CODEX are not configured.", true)

    override fun candidate(resultPath: Path): String = resultPath.readText()
}

class ClaudeProviderAdapter(private val credentialPath: Path?) : ProviderAdapter {
    override fun credentials(): Path = credentialPath
        ?: throw JobFailure("PROVIDER_UNAVAILABLE", "Credentials for CLAUDE are not configured.", true)

    override fun candidate(resultPath: Path): String = resultPath.readText()
}

data class JournalEntry(val claim: ClaimedJob)

class WorkerJournal(private val workRoot: Path, private val mapper: ObjectMapper) {
    private val random = SecureRandom()
    private val journalDir = workRoot.resolve("journal").also(Path::createDirectories)
    private val key = loadOrCreateKey()

    fun save(entry: JournalEntry) {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(mapper.writeValueAsBytes(entry))
        val data = Base64.getEncoder().encodeToString(iv + encrypted)
        val target = journalDir.resolve("${entry.claim.job.id}.journal")
        val temporary = journalDir.resolve(".${entry.claim.job.id}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, data, StandardOpenOption.CREATE_NEW)
        setOwnerOnly(temporary)
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun entries(): List<JournalEntry> = if (!journalDir.exists()) emptyList() else Files.list(journalDir).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".journal") }.map { path ->
            runCatching {
                val bytes = Base64.getDecoder().decode(path.readText())
                require(bytes.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                mapper.readValue(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), JournalEntry::class.java)
            }.getOrElse {
                Files.move(path, path.resolveSibling(path.fileName.toString() + ".corrupt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                null
            }
        }.filter { it != null }.map { it!! }.toList()
    }

    fun remove(jobId: String) { journalDir.resolve("$jobId.journal").deleteIfExists() }

    private fun loadOrCreateKey(): SecretKeySpec {
        val path = workRoot.resolve("journal.key")
        val bytes = if (path.exists()) path.readBytes() else ByteArray(32).also(random::nextBytes).also {
            Files.write(path, it, StandardOpenOption.CREATE_NEW)
            setOwnerOnly(path)
        }
        require(bytes.size == 32) { "Worker journal key must be 256 bits." }
        return SecretKeySpec(bytes, "AES")
    }

    private fun setOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) }
    }
}

class JobFailure(val code: String, message: String, val retryable: Boolean) : RuntimeException(message)

class RuntimeClient(private val config: WorkerConfig, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    fun register(body: WorkerRegistrationRequest): WorkerView = post("/v1/workers/register", body, WorkerView::class.java)!!
    fun claim(body: ClaimRequest): ClaimedJob? = post("/v1/workers/${config.workerId}/claims", body, ClaimedJob::class.java)
    fun heartbeat(claim: ClaimedJob, bootId: String): HeartbeatResponse = post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/heartbeat", HeartbeatRequest(claim.attemptId, claim.fencingToken, bootId), HeartbeatResponse::class.java)!!
    fun progress(claim: ClaimedJob, phase: String, percent: Int?, message: String?) { post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/progress", ProgressRequest(claim.attemptId, claim.fencingToken, phase, percent, message), Void::class.java) }
    fun transcript(claim: ClaimedJob, sequence: Long, kind: TranscriptKind, value: String) {
        val cleaned = redact(value, 100_000)
        post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/transcript", AppendTranscriptRequest(claim.attemptId, claim.fencingToken, "${claim.attemptId}-$sequence", sequence, kind, cleaned), TranscriptPartView::class.java)
    }
    fun complete(claim: ClaimedJob, result: JsonNode) { post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/complete", CompleteAttemptRequest(claim.attemptId, claim.fencingToken, result), Void::class.java) }
    fun startOutputAttempt(claim: ClaimedJob, idempotencyKey: String): OutputAttemptView = post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/output-attempts", StartOutputAttemptRequest(claim.attemptId, claim.fencingToken, idempotencyKey), OutputAttemptView::class.java)!!
    fun submitOutputCandidate(claim: ClaimedJob, outputAttemptId: String, candidate: String): SubmitOutputCandidateResponse = post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/output-candidates", SubmitOutputCandidateRequest(claim.attemptId, claim.fencingToken, outputAttemptId, candidate), SubmitOutputCandidateResponse::class.java)!!
    fun finalizeOutput(claim: ClaimedJob, outputAttemptId: String) { post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/output-finalization", FinalizeAcceptedOutputRequest(claim.attemptId, claim.fencingToken, outputAttemptId), Void::class.java) }
    fun fail(claim: ClaimedJob, code: String, message: String, retryable: Boolean) { post("/v1/workers/${config.workerId}/jobs/${claim.job.id}/fail", FailAttemptRequest(claim.attemptId, claim.fencingToken, code, message, retryable), Void::class.java) }
    fun uploadArtifact(claim: ClaimedJob, filename: String, mimeType: String, sha256: String, content: ByteArray) {
        val request = HttpRequest.newBuilder(URI.create(config.serverUrl + "/v1/workers/${config.workerId}/jobs/${claim.job.id}/artifacts"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer ${config.token}")
            .header("Content-Type", "application/octet-stream")
            .header("X-Attempt-Id", claim.attemptId)
            .header("X-Fencing-Token", claim.fencingToken)
            .header("X-Filename", filename)
            .header("X-Mime-Type", mimeType)
            .header("X-Content-SHA256", sha256)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("Artifact upload returned HTTP ${response.statusCode()}: ${safe(response.body())}")
    }

    private fun <T> post(path: String, body: Any, type: Class<T>): T? {
        val request = HttpRequest.newBuilder(URI.create(config.serverUrl + path)).timeout(Duration.ofSeconds(35))
            .header("Authorization", "Bearer ${config.token}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("Runtime returned HTTP ${response.statusCode()}: ${safe(response.body())}")
        if (response.statusCode() == 204 || type == Void::class.java) return null
        return mapper.readValue(response.body(), type)
    }
}

object EnvFiles {
    fun load(root: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        root.resolve("properties.default.env").takeIf(Path::exists)?.let {
            result.putAll(SecureEnvFiles.parse(it, Regex("[A-Z][A-Z0-9_]*")))
        }
        val local = root.resolve("properties.env")
        require(local.exists()) { "$local is required for a laptop worker" }
        SecureEnvFiles.requireOwnerOnly(local)
        result.putAll(SecureEnvFiles.parse(local, Regex("[A-Z][A-Z0-9_]*")))
        result.putAll(System.getenv())
        return result
    }
}

object ProjectCredentials {
    private val name = Regex("[A-Z][A-Z0-9_]*__[A-Z][A-Z0-9_]*")
    private val forbiddenPrefixes = setOf("AR", "CODEX", "CLAUDE", "OPENAI", "ANTHROPIC", "GITHUB", "GH")

    fun load(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        SecureEnvFiles.requireOwnerOnly(path)
        return SecureEnvFiles.parse(path, name).also { values ->
            values.keys.forEach { key -> require(key.substringBefore("__") !in forbiddenPrefixes) { "Forbidden project credential key $key" } }
        }
    }
}

object SecureEnvFiles {
    fun requireOwnerOnly(path: Path) {
        require(!path.isSymbolicLink() && path.isRegularFile()) { "$path must be a regular non-symlink file" }
        runCatching { Files.getPosixFilePermissions(path) }.getOrNull()?.let { permissions ->
            require(permissions.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "$path must have mode 0600" }
            require(PosixFilePermission.OWNER_READ in permissions && PosixFilePermission.OWNER_WRITE in permissions) { "$path must be owner-readable and owner-writable" }
        }
    }

    fun parse(path: Path, keyPattern: Regex): Map<String, String> {
        val result = linkedMapOf<String, String>()
        path.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachIndexed
            require('=' in trimmed) { "Invalid env line ${index + 1} in $path" }
            val (rawKey, rawValue) = trimmed.split('=', limit = 2)
            val key = rawKey.trim()
            require(keyPattern.matches(key)) { "Invalid env key $key in $path" }
            require(key !in result) { "Duplicate env key $key in $path" }
            val value = rawValue.trim().removeSurrounding("\"").removeSurrounding("'")
            require('\n' !in value && '\r' !in value && '\u0000' !in value) { "Invalid control character in $key" }
            result[key] = value
        }
        return result
    }
}

object SecretRedactor {
    @Volatile private var values: List<String> = emptyList()
    fun configure(secretValues: Collection<String>) { values = secretValues.filter { it.length >= 4 }.distinct().sortedByDescending(String::length) }
    fun clean(value: String): String = values.fold(value) { current, secret -> current.replace(secret, "[REDACTED]") }
    fun contains(value: String): Boolean = values.any(value::contains)
    fun contains(value: ByteArray): Boolean = values.any { secret -> value.indexOfSubsequence(secret.toByteArray()) >= 0 }
}

private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) if (this[start + offset] != needle[offset]) { matches = false; break }
        if (matches) return start
    }
    return -1
}

fun redact(value: String?, maxLength: Int): String = SecretRedactor.clean(value.orEmpty())
    .replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer [REDACTED]")
    .replace(Regex("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
    .take(maxLength)

fun safe(value: String?): String = redact(value, 2_000)

fun deleteTree(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

fun cleanupOrphanAttempts(workRoot: Path, activeJobIds: Set<String>) {
    if (!workRoot.exists()) return
    Files.list(workRoot).use { paths -> paths
        .filter { it.isDirectory() && it.fileName.toString() != "journal" && it.fileName.toString() !in activeJobIds }
        .forEach { runCatching { deleteTree(it) } }
    }
}
