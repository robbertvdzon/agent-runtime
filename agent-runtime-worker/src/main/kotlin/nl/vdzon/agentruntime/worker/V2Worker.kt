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

class V2WorkerExecutor(private val config: WorkerConfig, private val mapper: ObjectMapper, private val bootId: String) {
    private val client = V2RuntimeClient(config, mapper)
    private val repositories = V2RepositorySupport(config.repositoryAliases)
    private var enabled = false
    private val capabilities: Set<ExecutorCapability> = buildSet {
        config.advertisedModels[Provider.CODEX].orEmpty().forEach { model ->
            add(ExecutorCapability("openai", model, ExecutionMode.SUBSCRIPTION, setOf(TaskType.STRUCTURED_GENERATION, TaskType.REPOSITORY_AGENT)))
        }
        config.advertisedModels[Provider.CLAUDE].orEmpty().forEach { model ->
            add(ExecutorCapability("anthropic", model, ExecutionMode.SUBSCRIPTION, setOf(TaskType.STRUCTURED_GENERATION, TaskType.REPOSITORY_AGENT)))
        }
    }

    fun register() {
        if (capabilities.isEmpty()) return
        enabled = runCatching {
            client.register(
                WorkerRegistrationRequest(
                    config.workerId, bootId, capabilities, config.projectCredentials.keys,
                    config.repositoryAliases.keys, 1, mapOf("worker" to "0.3.0"),
                ),
            )
            true
        }.getOrElse {
            System.err.println("Agent Runtime v2 is not available yet; continuing with v1: ${safe(it.message)}")
            false
        }
    }

    fun claim(): ClaimedJob? = if (!enabled) null else client.claim(ClaimRequest(bootId, capabilities, 2))

    fun execute(claim: ClaimedJob) {
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
            val exit = runContainer(claim, workspace, task)
            if (exit != 0) throw JobFailure("ENGINE_FAILED", "Provider process exited with code $exit.", exit in setOf(124, 137))
            repositoryState?.let { repositories.verifyAgentDidNotMutate(it, workspace) }
            client.progress(claim, "UPLOADING_OUTPUT", 85, "Uploading declared output artifacts.")
            val outputIds = uploadOutputs(claim, task)
            val resultPath = task.resolve("output/result.json")
            if (!resultPath.isRegularFile() || resultPath.fileSize() > 1024L * 1024) throw JobFailure("RESULT_TOO_LARGE", "Provider did not produce a bounded result.json.", true)
            val result = mapper.readTree(resultPath.toFile())
            if (SecretRedactor.contains(result.toString(), selectedSensitiveValues(claim.request.environmentKeys, config.projectCredentials))) {
                throw JobFailure("SECRET_EXPOSURE_BLOCKED", "Provider result contained a selected sensitive value.", false)
            }
            val inputBytes = task.resolve("input/objects").takeIf(Path::exists)?.let { inputRoot ->
                Files.walk(inputRoot).use { paths -> paths.filter(Path::isRegularFile).mapToLong(Path::fileSize).sum() }
            } ?: 0L
            client.measuredUsage(claim, ((claim.request.input.instruction.length + inputBytes) / 4).coerceAtLeast(1), (result.toString().length / 4).coerceAtLeast(1).toLong())
            client.log(claim, LogKind.SYSTEM, "Execution finished in ${Duration.between(started, Instant.now()).seconds} seconds.")
            complete(claim, workspace, repositoryState, result, outputIds)
        } catch (failure: JobFailure) {
            runCatching { client.fail(claim, failure.code, failure.message.orEmpty(), failure.retryable) }
        } catch (error: Exception) {
            runCatching { client.fail(claim, "WORKER_ERROR", safe(error.message), true) }
        } finally {
            runCatching { deleteTree(root) }
        }
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

    private fun complete(claim: ClaimedJob, workspace: Path, state: V2RepositoryState?, result: JsonNode, outputIds: Set<String>) {
        val checkout = claim.request.repositoryCheckout
        if (checkout == null) {
            client.submit(claim, result, outputIds, null)
            return
        }
        val captured = state ?: throw JobFailure("INVALID_REPOSITORY_CHECKOUT", "Repository checkout state is unavailable.", false)
        when (checkout.publicationMode) {
            RepositoryPublicationMode.NONE -> client.submit(
                claim, result, outputIds,
                RepositoryResult(checkout.alias, checkout.branch, captured.checkoutCommitSha, RepositoryPublicationStatus.NONE),
            )
            RepositoryPublicationMode.COMMIT_AND_PUSH -> {
                val repositoryResult = repositories.prepareCommit(
                    checkout, captured, workspace, claim.job.id,
                    selectedSensitiveValues(claim.request.environmentKeys, config.projectCredentials),
                )
                if (repositoryResult.publicationStatus == RepositoryPublicationStatus.NO_CHANGES) {
                    client.submit(claim, result, outputIds, repositoryResult)
                    return
                }
                val intendedCommitSha = requireNotNull(repositoryResult.commitSha)
                client.preparePublication(claim, result, outputIds, repositoryResult)
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

    private fun runContainer(claim: ClaimedJob, workspace: Path, task: Path): Int {
        val provider = claim.job.execution.vendorId
        val engine = if (provider == "openai") "CODEX" else "CLAUDE"
        val credentials = if (engine == "CODEX") config.codexCredentials else config.claudeCredentials
        if (engine == "CODEX" && credentials == null) throw JobFailure("PROVIDER_UNAVAILABLE", "Codex credentials are unavailable.", true)
        if (engine == "CLAUDE" && credentials == null && config.claudeOauthToken.isNullOrBlank()) throw JobFailure("PROVIDER_UNAVAILABLE", "Claude credentials are unavailable.", true)
        val name = "ar-v2-${claim.job.id.take(8)}-${claim.attempt.id.take(8)}"
        val command = mutableListOf(
            "docker", "run", "--pull", "always", "--rm", "--name", name, "--memory", "8g", "--cpus", "4", "--pids-limit", "1024",
            "-v", "$workspace:/work", "-v", "${task.resolve("input")}:/job/input:ro", "-v", "${task.resolve("secrets")}:/job/secrets:ro",
            "-v", "${task.resolve("docs")}:/job/docs:ro", "-v", "${task.resolve("output")}:/job/output",
        )
        if (claim.request.repositoryCheckout != null) command += listOf("-v", "${workspace.resolve(".git")}:/work/.git:ro", "-e", "GIT_OPTIONAL_LOCKS=0")
        credentials?.let { command += listOf("-v", "${it.toAbsolutePath()}:/credential-source:ro") }
        if (engine == "CLAUDE" && !config.claudeOauthToken.isNullOrBlank()) command += listOf("-e", "CLAUDE_CODE_OAUTH_TOKEN")
        command += listOf("-e", "AR_ENGINE=$engine", "-e", "AR_MODEL=${claim.job.execution.model}", "-e", "AR_JOB_KIND=${claim.job.jobKind.name}", "-e", "AR_RESULT_FILE=/job/output/result.json", config.executionImage)
        val process = ProcessBuilder(command).redirectErrorStream(true).also {
            if (engine == "CLAUDE" && !config.claudeOauthToken.isNullOrBlank()) it.environment()["CLAUDE_CODE_OAUTH_TOKEN"] = config.claudeOauthToken
        }.start()
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val cleaned = redact(line, 8192)
            if (cleaned.isNotBlank()) runCatching { client.log(claim, LogKind.AGENT_TEXT, cleaned) }
        } } }.apply { start() }
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            val heartbeat = client.heartbeat(claim)
            if (!heartbeat.accepted || heartbeat.fenced || heartbeat.cancelRequested || Instant.now().isAfter(claim.attemptDeadline)) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                throw JobFailure(if (heartbeat.cancelRequested) "CANCELLED" else "EXECUTION_TIMEOUT", "Execution was stopped.", !heartbeat.cancelRequested)
            }
        }
        reader.join(5000)
        return process.exitValue()
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
    fun measuredUsage(claim: ClaimedJob, inputTokens: Long, outputTokens: Long) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/usage-events", AppendUsageRequest(claim.fencingToken, UUID.randomUUID().toString(), Instant.now(), listOf(UsageMetricValue(UsageMetric.INPUT_TOKENS, inputTokens.toString(), UsageUnit.TOKEN), UsageMetricValue(UsageMetric.OUTPUT_TOKENS, outputTokens.toString(), UsageUnit.TOKEN)), source = UsageSource.WORKER_MEASURED), Void::class.java) }
    fun submit(claim: ClaimedJob, result: JsonNode, objectIds: Set<String>, repositoryResult: RepositoryResult?) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/result", SubmitResultRequest(claim.fencingToken, result, objectIds, repositoryResult), Void::class.java) }
    fun preparePublication(claim: ClaimedJob, result: JsonNode, objectIds: Set<String>, repositoryResult: RepositoryResult) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/repository-publication", PrepareRepositoryPublicationRequest(claim.fencingToken, result, objectIds, repositoryResult), RepositoryPublicationIntentView::class.java) }
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
