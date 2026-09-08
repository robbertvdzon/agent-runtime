package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.Provider
import nl.vdzon.agentruntime.contracts.v2.AppendLogRequest
import nl.vdzon.agentruntime.contracts.v2.AppendUsageRequest
import nl.vdzon.agentruntime.contracts.v2.AttemptAuth
import nl.vdzon.agentruntime.contracts.v2.ClaimRequest
import nl.vdzon.agentruntime.contracts.v2.ClaimedJob
import nl.vdzon.agentruntime.contracts.v2.CreateOutputUploadRequest
import nl.vdzon.agentruntime.contracts.v2.ExecutionMode
import nl.vdzon.agentruntime.contracts.v2.ExecutorCapability
import nl.vdzon.agentruntime.contracts.v2.FailAttemptRequest
import nl.vdzon.agentruntime.contracts.v2.HeartbeatResponse
import nl.vdzon.agentruntime.contracts.v2.JobKind
import nl.vdzon.agentruntime.contracts.v2.LogKind
import nl.vdzon.agentruntime.contracts.v2.ProgressRequest
import nl.vdzon.agentruntime.contracts.v2.SubmitResultRequest
import nl.vdzon.agentruntime.contracts.v2.TaskType
import nl.vdzon.agentruntime.contracts.v2.UploadView
import nl.vdzon.agentruntime.contracts.v2.UsageMetric
import nl.vdzon.agentruntime.contracts.v2.UsageMetricValue
import nl.vdzon.agentruntime.contracts.v2.UsageSource
import nl.vdzon.agentruntime.contracts.v2.UsageUnit
import nl.vdzon.agentruntime.contracts.v2.WorkerRegistrationRequest
import nl.vdzon.agentruntime.contracts.v2.WorkerView
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

class V2WorkerExecutor(private val config:WorkerConfig,private val mapper:ObjectMapper,private val bootId:String) {
    private val client=V2RuntimeClient(config,mapper)
    private var enabled=false
    private val capabilities:Set<ExecutorCapability> = buildSet {
        config.advertisedModels[Provider.CODEX].orEmpty().forEach { model -> add(ExecutorCapability("openai",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION,TaskType.REPOSITORY_AGENT))) }
        config.advertisedModels[Provider.CLAUDE].orEmpty().forEach { model -> add(ExecutorCapability("anthropic",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION,TaskType.REPOSITORY_AGENT))) }
    }

    fun register() {
        if(capabilities.isEmpty())return
        enabled=runCatching { client.register(WorkerRegistrationRequest(config.workerId,bootId,capabilities,config.projectCredentials.keys,1,mapOf("worker" to "0.2.0")));true }.getOrElse {
            System.err.println("Agent Runtime v2 is not available yet; continuing with v1: ${safe(it.message)}")
            false
        }
    }
    fun claim():ClaimedJob?=if(!enabled)null else client.claim(ClaimRequest(bootId,capabilities,2))

    fun execute(claim:ClaimedJob) {
        val root=config.workRoot.resolve("v2-${claim.job.id}").also(Path::createDirectories);val workspace=root.resolve("workspace").also(Path::createDirectories);val task=root.resolve("job")
        try {
            client.progress(claim,"PREPARING",5,"Preparing streamed inputs.")
            prepare(claim,workspace,task)
            val started=Instant.now();val exit=runContainer(claim,workspace,task)
            if(exit!=0)throw JobFailure("ENGINE_FAILED","Provider process exited with code $exit.",exit in setOf(124,137))
            client.progress(claim,"UPLOADING_OUTPUT",85,"Uploading declared output artifacts.")
            val outputIds=uploadOutputs(claim,task)
            val resultPath=task.resolve("output/result.json")
            if(!resultPath.isRegularFile()||resultPath.fileSize()>1024L*1024)throw JobFailure("RESULT_TOO_LARGE","Provider did not produce a bounded result.json.",true)
            val result=if(claim.job.jobKind==JobKind.REPOSITORY_WORK)publishRepository(claim,workspace) else mapper.readTree(resultPath.toFile())
            val inputBytes=task.resolve("input/objects").takeIf(Path::exists)?.let{root->Files.walk(root).use{paths->paths.filter(Path::isRegularFile).mapToLong(Path::fileSize).sum()}}?:0L
            client.measuredUsage(claim,((claim.request.input.instruction.length+inputBytes)/4).coerceAtLeast(1), (result.toString().length/4).coerceAtLeast(1).toLong())
            client.log(claim,LogKind.SYSTEM,"Execution finished in ${Duration.between(started,Instant.now()).seconds} seconds.")
            client.submit(claim,result,outputIds)
        } catch(failure:JobFailure) { runCatching{client.fail(claim,failure.code,failure.message.orEmpty(),failure.retryable)}
        } catch(error:Exception) { runCatching{client.fail(claim,"WORKER_ERROR",safe(error.message),true)}
        } finally { runCatching{deleteTree(root)} }
    }

    private fun prepare(claim:ClaimedJob,workspace:Path,task:Path) {
        val input=task.resolve("input").also(Path::createDirectories);task.resolve("output/artifacts").createDirectories();task.resolve("secrets").createDirectories();task.resolve("docs").createDirectories()
        claim.request.input.objects.forEach { ref ->
            val dir=input.resolve("objects").resolve(ref.name).also(Path::createDirectories)
            client.download(claim,ref.objectId,dir.resolve("content"))
        }
        val artifactInstructions=claim.request.output.artifacts.joinToString("\n"){"- ${it.name}: write a ${it.mimeTypes.joinToString(" or ")} file to /job/output/artifacts/${it.name}; required=${it.required}; maxBytes=${it.maxBytes?:"runtime default"}"}
        input.resolve("prompt.md").writeText("""${claim.request.input.instruction.trim()}

Read uploaded inputs below /job/input/objects. Return the bounded JSON result as your final response. Do not put large content in JSON.
${if(artifactInstructions.isBlank())"No output artifacts are declared." else "Declared artifacts:\n$artifactInstructions"}
""".trimIndent())
        input.resolve("response-schema.json").writeText(claim.request.output.resultSchema?.toString()?:"{}")
        if(claim.job.jobKind==JobKind.REPOSITORY_WORK) {
            val request=claim.request.repositoryRequest?:throw JobFailure("INVALID_REPOSITORY_REQUEST","Missing repository request.",false)
            val url=config.repositoryAliases[request.alias]?:throw JobFailure("UNKNOWN_REPOSITORY_ALIAS","Unknown repository alias ${request.alias}.",false)
            command(listOf("git","clone",url,workspace.toString()),config.workRoot,300);command(listOf("git","checkout",request.baseBranch),workspace,60);command(listOf("git","checkout","-b","agent-runtime/${claim.job.id}"),workspace,60)
        } else claim.request.repositorySnapshot?.let { snapshot ->
            command(listOf("git","clone","--filter=blob:none","--no-checkout",snapshot.url,workspace.toString()),config.workRoot,300);command(listOf("git","checkout","--detach",snapshot.commitSha),workspace,120);runCatching{command(listOf("git","remote","remove","origin"),workspace,30)}
        }
    }

    private fun runContainer(claim:ClaimedJob,workspace:Path,task:Path):Int {
        val provider=claim.job.execution.vendorId;val engine=if(provider=="openai")"CODEX" else "CLAUDE"
        val credentials=if(engine=="CODEX")config.codexCredentials else config.claudeCredentials
        if(engine=="CODEX"&&credentials==null)throw JobFailure("PROVIDER_UNAVAILABLE","Codex credentials are unavailable.",true)
        if(engine=="CLAUDE"&&credentials==null&&config.claudeOauthToken.isNullOrBlank())throw JobFailure("PROVIDER_UNAVAILABLE","Claude credentials are unavailable.",true)
        val name="ar-v2-${claim.job.id.take(8)}-${claim.attempt.id.take(8)}"
        val command=mutableListOf("docker","run","--pull","always","--rm","--name",name,"--memory","8g","--cpus","4","--pids-limit","1024","-v","$workspace:/work","-v","${task.resolve("input")}:/job/input:ro","-v","${task.resolve("secrets")}:/job/secrets:ro","-v","${task.resolve("docs")}:/job/docs:ro","-v","${task.resolve("output")}:/job/output")
        credentials?.let{command+=listOf("-v","${it.toAbsolutePath()}:/credential-source:ro")}
        if(engine=="CLAUDE"&&!config.claudeOauthToken.isNullOrBlank())command+=listOf("-e","CLAUDE_CODE_OAUTH_TOKEN")
        command+=listOf("-e","AR_ENGINE=$engine","-e","AR_MODEL=${claim.job.execution.model}","-e","AR_JOB_KIND=${claim.job.jobKind.name}","-e","AR_RESULT_FILE=/job/output/result.json",config.executionImage)
        val process=ProcessBuilder(command).redirectErrorStream(true).also{if(engine=="CLAUDE"&&!config.claudeOauthToken.isNullOrBlank())it.environment()["CLAUDE_CODE_OAUTH_TOKEN"]=config.claudeOauthToken}.start()
        val reader=Thread{process.inputStream.bufferedReader().useLines{lines->lines.forEach{line->val cleaned=redact(line,8192);if(cleaned.isNotBlank())runCatching{client.log(claim,LogKind.AGENT_TEXT,cleaned)}}}}.apply{start()}
        while(!process.waitFor(1,TimeUnit.SECONDS)) { val heartbeat=client.heartbeat(claim);if(!heartbeat.accepted||heartbeat.fenced||heartbeat.cancelRequested||Instant.now().isAfter(claim.attemptDeadline)){process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();throw JobFailure(if(heartbeat.cancelRequested)"CANCELLED" else "EXECUTION_TIMEOUT","Execution was stopped.",!heartbeat.cancelRequested)} }
        reader.join(5000);return process.exitValue()
    }

    private fun uploadOutputs(claim:ClaimedJob,task:Path):Set<String> = claim.request.output.artifacts.mapNotNull { declaration ->
        val path=task.resolve("output/artifacts/${declaration.name}")
        if(!path.isRegularFile()) { if(declaration.required)throw JobFailure("MISSING_REQUIRED_ARTIFACT","Required artifact ${declaration.name} is missing.",true);return@mapNotNull null }
        if(path.isSymbolicLink())throw JobFailure("UNSAFE_ARTIFACT","Artifact may not be a symbolic link.",false)
        val size=path.fileSize();declaration.maxBytes?.let{if(size>it)throw JobFailure("OUTPUT_TOO_LARGE","Artifact ${declaration.name} is too large.",false)}
        val mime=Files.probeContentType(path)?:declaration.mimeTypes.first();if(mime !in declaration.mimeTypes)throw JobFailure("OUTPUT_MIME_NOT_ALLOWED","Artifact ${declaration.name} has MIME $mime.",false)
        client.upload(claim,declaration.name,path,mime)
    }.toSet()

    private fun publishRepository(claim:ClaimedJob,workspace:Path):JsonNode {
        val changed=command(listOf("git","status","--porcelain"),workspace,30)
        if(changed.isBlank())throw JobFailure("NO_REPOSITORY_CHANGES","Agent produced no repository changes.",false)
        changed.lineSequence().filter(String::isNotBlank).map{it.drop(3).substringAfter(" -> ")}.forEach{relative->
            val path=workspace.resolve(relative).normalize();if(!path.startsWith(workspace)||path.isSymbolicLink())throw JobFailure("UNSAFE_REPOSITORY_OUTPUT","Repository output contains an unsafe path.",false)
            if(path.isRegularFile()&&path.fileSize()>20L*1024*1024)throw JobFailure("FILE_TOO_LARGE","Repository output contains a file over 20 MiB.",false)
            if(path.fileName.toString() in setOf("secrets.env",".env","id_rsa"))throw JobFailure("SECRET_FILE_BLOCKED","Repository output contains a forbidden secret file.",false)
        }
        val diff=command(listOf("git","diff","--stat","HEAD"),workspace,30).take(10_000)
        command(listOf("git","add","--all"),workspace,30);command(listOf("git","commit","-m","agent-runtime: job ${claim.job.id}"),workspace,60)
        val commit=command(listOf("git","rev-parse","HEAD"),workspace,30).trim();val request=claim.request.repositoryRequest!!;var pullRequest:String?=null
        if(request.publish){command(listOf("git","push","-u","origin","agent-runtime/${claim.job.id}"),workspace,180);pullRequest=runCatching{command(listOf("gh","pr","create","--fill","--base",request.baseBranch,"--head","agent-runtime/${claim.job.id}"),workspace,120).trim()}.getOrNull()}
        return mapper.createObjectNode().apply{put("branch","agent-runtime/${claim.job.id}");put("commitSha",commit);put("diffStat",diff);put("published",request.publish);pullRequest?.let{put("pullRequestUrl",it)}}
    }

    private fun command(argv:List<String>,cwd:Path,timeout:Long):String {val process=ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();val output=StringBuilder();val reader=Thread{process.inputStream.bufferedReader().useLines{lines->lines.forEach{if(output.length<100_000)output.appendLine(it)}}}.apply{start()};if(!process.waitFor(timeout,TimeUnit.SECONDS)){process.destroyForcibly();throw JobFailure("COMMAND_TIMEOUT","${argv.first()} timed out.",true)};reader.join(5000);val text=output.toString();if(process.exitValue()!=0)throw JobFailure("COMMAND_FAILED",safe(text),true);return text}
}

class V2RuntimeClient(private val config:WorkerConfig,private val mapper:ObjectMapper) {
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    fun register(body:WorkerRegistrationRequest)=post("/v2/workers/register",body,WorkerView::class.java)!!
    fun claim(body:ClaimRequest)=post("/v2/workers/${config.workerId}/claims",body,ClaimedJob::class.java)
    fun heartbeat(claim:ClaimedJob)=post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/heartbeat",AttemptAuth(claim.attempt.id,claim.fencingToken),HeartbeatResponse::class.java)!!
    fun progress(claim:ClaimedJob,phase:String,percent:Int?,message:String?) { post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/progress",ProgressRequest(claim.attempt.id,claim.fencingToken,phase,percent,message),Void::class.java) }
    fun log(claim:ClaimedJob,kind:LogKind,text:String){post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/logs",AppendLogRequest(claim.fencingToken,UUID.randomUUID().toString(),kind,text.take(8192),observedAt=Instant.now()),Void::class.java)}
    fun measuredUsage(claim:ClaimedJob,inputTokens:Long,outputTokens:Long){post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/usage-events",AppendUsageRequest(claim.fencingToken,UUID.randomUUID().toString(),Instant.now(),listOf(UsageMetricValue(UsageMetric.INPUT_TOKENS,inputTokens.toString(),UsageUnit.TOKEN),UsageMetricValue(UsageMetric.OUTPUT_TOKENS,outputTokens.toString(),UsageUnit.TOKEN)),source=UsageSource.WORKER_MEASURED),Void::class.java)}
    fun submit(claim:ClaimedJob,result:JsonNode,objectIds:Set<String>){post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/result",SubmitResultRequest(claim.fencingToken,result,objectIds),Void::class.java)}
    fun fail(claim:ClaimedJob,code:String,message:String,retryable:Boolean){post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/attempts/${claim.attempt.id}/fail",FailAttemptRequest(claim.fencingToken,code,message,retryable),Void::class.java)}

    fun download(claim:ClaimedJob,objectId:String,target:Path) {
        val request=HttpRequest.newBuilder(uri("/v2/workers/${config.workerId}/jobs/${claim.job.id}/objects/$objectId/content")).timeout(Duration.ofMinutes(30)).header("Authorization","Bearer ${config.token}").header("X-Attempt-Id",claim.attempt.id).header("X-Fencing-Token",claim.fencingToken).GET().build()
        val response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());if(response.statusCode()!=200)throw IOException("Input download returned HTTP ${response.statusCode()}")
        response.body().use{input->Files.newOutputStream(target,StandardOpenOption.CREATE_NEW).use(input::transferTo)}
    }

    fun upload(claim:ClaimedJob,name:String,path:Path,mime:String):String {
        val sha=Files.newInputStream(path).use{input->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(128*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};HexFormat.of().formatHex(digest.digest())}
        val reservation=post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/output-objects",CreateOutputUploadRequest(claim.attempt.id,claim.fencingToken,name,path.fileName.toString(),mime,path.fileSize(),sha),UploadView::class.java)!!
        var offset=reservation.offset
        while(offset<path.fileSize()) {
            val current=offset
            val publisher=HttpRequest.BodyPublishers.ofInputStream{Files.newInputStream(path).also{it.skipNBytes(current)}}
            val request=HttpRequest.newBuilder(uri("/v2/workers/${config.workerId}/jobs/${claim.job.id}/output-objects/${reservation.uploadId}")).timeout(Duration.ofMinutes(30)).header("Authorization","Bearer ${config.token}").header("X-Attempt-Id",claim.attempt.id).header("X-Fencing-Token",claim.fencingToken).header("Upload-Offset",current.toString()).header("Content-Type","application/offset+octet-stream").method("PATCH",publisher).build()
            val response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=204)throw IOException("Output upload returned HTTP ${response.statusCode()}: ${safe(response.body())}");offset=response.headers().firstValue("Upload-Offset").orElseThrow().toLong()
        }
        val complete=post("/v2/workers/${config.workerId}/jobs/${claim.job.id}/output-objects/${reservation.uploadId}/complete",AttemptAuth(claim.attempt.id,claim.fencingToken),JsonNode::class.java)!!
        return complete.path("objectId").asText()
    }

    private fun <T> post(path:String,body:Any,type:Class<T>):T? {val request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(35)).header("Authorization","Bearer ${config.token}").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();val response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()==204)return null;if(response.statusCode() !in 200..299)throw IOException("Runtime returned HTTP ${response.statusCode()}: ${safe(response.body())}");return mapper.readValue(response.body(),type)}
    private fun uri(path:String)=URI.create(config.serverUrl+path)
}
