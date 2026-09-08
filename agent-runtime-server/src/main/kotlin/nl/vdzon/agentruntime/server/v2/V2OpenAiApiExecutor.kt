package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
class V2OpenAiApiExecutor(
    private val properties:RuntimeProperties,private val mapper:ObjectMapper,private val jobs:V2JobStore,
    private val jobService:V2JobService,private val objectStore:V2ObjectStore,private val blobs:FilesystemBlobStore,
    private val uploadService:V2UploadService,private val usage:V2UsageStore,
) {
    private val logger=LoggerFactory.getLogger(javaClass)
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    @Scheduled(fixedDelay=500)
    @Synchronized
    fun execute() {
        if(properties.openAiApiKey.isBlank())return
        val job=jobs.queued().firstOrNull{it.view.execution.mode==ExecutionMode.API&&it.view.execution.vendorId=="openai"}?:return
        val token=UUID.randomUUID().toString()+UUID.randomUUID();val now=Instant.now();val attempt=jobs.createAttempt(job,"server-openai-api","server",token,now.plusSeconds(properties.leaseSeconds),now.plusSeconds(job.request.executionTimeoutSeconds.toLong()))
        try {
            when(job.view.taskType){TaskType.STRUCTURED_GENERATION->structured(job,attempt);TaskType.TRANSCRIPTION->transcribe(job,attempt);else->error("Unsupported API task")}
        } catch(error:ProviderFailure) { jobs.failAttempt(jobs.find(job.view.id)!!,attempt.view.id,error.code,error.message.orEmpty(),error.retryable)
        } catch(error:Exception) { logger.error("OpenAI API job {} failed",job.view.id,error);jobs.failAttempt(jobs.find(job.view.id)!!,attempt.view.id,"API_EXECUTION_ERROR",error.message?:"API execution failed",true) }
    }

    private fun structured(job:StoredV2Job,attempt:StoredV2Attempt) {
        jobs.progress(job.view.id,attempt.view.id,"UPLOADING_PROVIDER_INPUTS",10,"Preparing provider file inputs.")
        val providerFiles=mutableListOf<String>()
        try {
            objectStore.inputObjects(job.view.id).forEach{(_,record)->providerFiles+=uploadProviderFile(record)}
            val content=mapper.createArrayNode().add(mapper.createObjectNode().put("type","input_text").put("text",job.request.input.instruction))
            providerFiles.forEach{fileId->content.add(mapper.createObjectNode().put("type","input_file").put("file_id",fileId))}
            val payload=mapper.createObjectNode().put("model",job.view.execution.model).put("store",false)
            payload.set<JsonNode>("input",mapper.createArrayNode().add(mapper.createObjectNode().put("role","user").set<JsonNode>("content",content)))
            job.request.output.resultSchema?.let{schema->payload.set<JsonNode>("text",mapper.createObjectNode().set<JsonNode>("format",mapper.createObjectNode().put("type","json_schema").put("name","agent_runtime_result").put("strict",true).set<JsonNode>("schema",schema)))}
            payload.set<JsonNode>("reasoning",mapper.createObjectNode().put("generate_summary","auto"))
            payload.put("stream",true)
            payload.set<JsonNode>("stream_options",mapper.createObjectNode().put("include_obfuscation",false))
            jobs.progress(job.view.id,attempt.view.id,"CALLING_PROVIDER",25,"Calling OpenAI Responses API.")
            val streamed=postResponsesStream(payload,job,attempt);val response=streamed.response
            recordResponseMetadata(job,attempt,response)
            val output=streamed.output.takeIf(String::isNotBlank)?:response.path("output_text").asText().takeIf(String::isNotBlank)?:response.path("output").flatMap{it.path("content").toList()}.firstOrNull{it.path("type").asText()=="output_text"}?.path("text")?.asText()?:throw ProviderFailure("PROVIDER_OUTPUT_MISSING","OpenAI returned no output text.",true)
            val result=runCatching{mapper.readTree(output)}.getOrElse{throw ProviderFailure("MODEL_OUTPUT_NOT_JSON","OpenAI output is not JSON.",true)}
            finish(job,attempt,result,emptySet())
        } finally { providerFiles.forEach{runCatching{deleteProviderFile(it)}} }
    }

    private fun transcribe(job:StoredV2Job,attempt:StoredV2Attempt) {
        val source=objectStore.inputObjects(job.view.id).singleOrNull()?.second?:throw ProviderFailure("INVALID_TRANSCRIPTION_INPUT","Transcription requires exactly one input object.",false)
        jobs.progress(job.view.id,attempt.view.id,"CALLING_PROVIDER",20,"Streaming audio to OpenAI transcription API.")
        val boundary="ar-${UUID.randomUUID()}";val prefix=("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n${job.view.execution.model}\r\n"+
            "--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n"+
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${source.view.filename.replace("\"","")}\"\r\nContent-Type: ${source.view.mimeType}\r\n\r\n").toByteArray()
        val suffix="\r\n--$boundary--\r\n".toByteArray()
        val publisher=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofInputStream{blobs.open(source.blobKey)},HttpRequest.BodyPublishers.ofByteArray(suffix))
        val response=send(HttpRequest.newBuilder(uri("/audio/transcriptions")).timeout(Duration.ofSeconds(job.request.executionTimeoutSeconds.toLong())).header("Content-Type","multipart/form-data; boundary=$boundary").POST(publisher).build())
        val responseJson=mapper.readTree(response);val text=responseJson.path("text").asText()
        if(text.isBlank())throw ProviderFailure("PROVIDER_OUTPUT_MISSING","OpenAI returned no transcript.",true)
        val outputIds=mutableSetOf<String>()
        job.request.output.artifacts.forEach{declaration->
            val mime=when{ "text/plain" in declaration.mimeTypes->"text/plain";"application/json" in declaration.mimeTypes->"application/json";else->throw ProviderFailure("OUTPUT_MIME_NOT_SUPPORTED","Transcription can produce text/plain or application/json.",false)}
            val bytes=if(mime=="application/json")responseJson.toString().toByteArray() else text.toByteArray()
            declaration.maxBytes?.let{if(bytes.size>it)throw ProviderFailure("OUTPUT_TOO_LARGE","Transcript exceeds declared maximum.",false)}
            val sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));val reserved=uploadService.reserveOutput(job.view.tenantId,job.view.id,attempt.view.id,declaration.name,"${declaration.name}.${if(mime=="application/json")"json" else "txt"}",mime,bytes.size.toLong(),sha)
            uploadService.append(job.view.tenantId,reserved.uploadId,0,ByteArrayInputStream(bytes),"OUTPUT");val ready=uploadService.complete(job.view.tenantId,reserved.uploadId,"OUTPUT");objectStore.link(job.view.id,ready.objectId,"OUTPUT",null,declaration.name,outputIds.size);outputIds+=ready.objectId
        }
        val result=mapper.createObjectNode().put("text",text)
        finish(job,attempt,result,outputIds)
    }

    private fun finish(job:StoredV2Job,attempt:StoredV2Attempt,result:JsonNode,outputIds:Set<String>) {
        val fresh=jobs.find(job.view.id)!!;val errors=jobService.validateResult(fresh,result,outputIds)
        if(errors.isNotEmpty()){uploadService.discardAttemptOutputs(job.view.id,attempt.view.id);jobs.rejectOutput(fresh,attempt.view.id,"MODEL_OUTPUT_SCHEMA_INVALID",errors.joinToString("; "){it.message});return}
        jobs.complete(job.view.id,attempt.view.id,result,usage.attemptSummary(attempt.view.id).usageQuality)
    }

    private fun recordResponseMetadata(job:StoredV2Job,attempt:StoredV2Attempt,response:JsonNode) {
        response.path("output").filter{it.path("type").asText()=="reasoning"}.flatMap{it.path("summary").toList()}.mapNotNull{it.path("text").asText().takeIf(String::isNotBlank)}.forEachIndexed{i,text->jobs.addEvent(job.view.id,attempt.view.id,EventType.LOG_MESSAGE,"CALLING_PROVIDER",null,LogKind.REASONING_SUMMARY,text,"openai-reasoning-$i",true,externalId="reasoning-$i")}
        val node=response.path("usage");val metrics=mutableListOf<UsageMetricValue>()
        fun add(name:String,metric:UsageMetric){node.path(name).takeIf{it.isNumber}?.let{metrics+=UsageMetricValue(metric,it.asText(),UsageUnit.TOKEN)}}
        add("input_tokens",UsageMetric.INPUT_TOKENS);add("output_tokens",UsageMetric.OUTPUT_TOKENS)
        node.path("input_tokens_details").path("cached_tokens").takeIf{it.isNumber}?.let{metrics+=UsageMetricValue(UsageMetric.CACHED_INPUT_TOKENS,it.asText(),UsageUnit.TOKEN)}
        node.path("output_tokens_details").path("reasoning_tokens").takeIf{it.isNumber}?.let{metrics+=UsageMetricValue(UsageMetric.REASONING_TOKENS,it.asText(),UsageUnit.TOKEN)}
        if(metrics.isNotEmpty())usage.append(job,attempt.view.id,AppendUsageRequest("internal","openai-${response.path("id").asText()}",Instant.now(),metrics,response.path("id").asText(),UsageSource.PROVIDER_REPORTED))
    }

    private fun uploadProviderFile(record:StoredV2Object):String {
        val boundary="ar-${UUID.randomUUID()}";val prefix=("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nuser_data\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${record.view.filename.replace("\"","")}\"\r\nContent-Type: ${record.view.mimeType}\r\n\r\n").toByteArray();val suffix="\r\n--$boundary--\r\n".toByteArray()
        val request=HttpRequest.newBuilder(uri("/files")).timeout(Duration.ofMinutes(30)).header("Content-Type","multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofInputStream{blobs.open(record.blobKey)},HttpRequest.BodyPublishers.ofByteArray(suffix))).build()
        return mapper.readTree(send(request)).path("id").asText().takeIf(String::isNotBlank)?:throw ProviderFailure("PROVIDER_FILE_UPLOAD_FAILED","OpenAI returned no file id.",true)
    }
    private data class StreamedResponse(val output:String,val response:JsonNode)
    private fun postResponsesStream(payload:JsonNode,job:StoredV2Job,attempt:StoredV2Attempt):StreamedResponse {
        val request=HttpRequest.newBuilder(uri("/responses")).timeout(Duration.ofSeconds(job.request.executionTimeoutSeconds.toLong())).header("Authorization","Bearer ${properties.openAiApiKey}").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()
        val response=http.send(request,HttpResponse.BodyHandlers.ofInputStream())
        if(response.statusCode() !in 200..299){val error=response.body().use{String(it.readNBytes(64*1024),StandardCharsets.UTF_8)};throw ProviderFailure("PROVIDER_ERROR","OpenAI returned HTTP ${response.statusCode()}: ${error.take(1000)}",response.statusCode()==429||response.statusCode()>=500)}
        val output=StringBuilder();var completed:JsonNode?=null;var sequence=0L
        response.body().bufferedReader().useLines{lines->lines.forEach{line->
            if(!line.startsWith("data: "))return@forEach
            val data=line.removePrefix("data: ");if(data=="[DONE]")return@forEach
            val event=runCatching{mapper.readTree(data)}.getOrNull()?:return@forEach
            when(event.path("type").asText()){
                "response.output_text.delta"->{val delta=event.path("delta").asText();if(output.length+delta.length>properties.v2ResultMaxBytes)throw ProviderFailure("RESULT_TOO_LARGE","Streamed JSON result exceeds the Runtime limit.",false);output.append(delta)}
                "response.reasoning_summary_text.delta"->{val text=event.path("delta").asText().take(8192);if(text.isNotBlank())jobs.addEvent(job.view.id,attempt.view.id,EventType.LOG_MESSAGE,"CALLING_PROVIDER",null,LogKind.REASONING_SUMMARY,text,event.path("item_id").asText("openai-reasoning"),false,externalId="openai-stream-${sequence++}")}
                "response.reasoning_summary_text.done"->{val text=event.path("text").asText().take(8192);if(text.isNotBlank())jobs.addEvent(job.view.id,attempt.view.id,EventType.LOG_MESSAGE,"CALLING_PROVIDER",null,LogKind.REASONING_SUMMARY,text,event.path("item_id").asText("openai-reasoning"),true,externalId="openai-stream-${sequence++}")}
                "response.completed"->completed=event.path("response")
                "response.failed"->throw ProviderFailure("PROVIDER_ERROR",event.path("response").path("error").path("message").asText("OpenAI response failed."),true)
            }
        }}
        return StreamedResponse(output.toString(),completed?:throw ProviderFailure("PROVIDER_STREAM_INCOMPLETE","OpenAI stream ended without response.completed.",true))
    }
    private fun deleteProviderFile(id:String){send(HttpRequest.newBuilder(uri("/files/$id")).DELETE().build())}
    private fun send(builder:HttpRequest):String {val request=HttpRequest.newBuilder(builder.uri()).method(builder.method(),builder.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).timeout(builder.timeout().orElse(Duration.ofMinutes(30))).headers(*builder.headers().map().flatMap{(k,v)->v.map{k to it}}.flatMap{listOf(it.first,it.second)}.toTypedArray()).header("Authorization","Bearer ${properties.openAiApiKey}").build();val response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());val bytes=response.body().use{it.readNBytes(64*1024*1024+1)};if(bytes.size>64*1024*1024)throw ProviderFailure("PROVIDER_RESPONSE_TOO_LARGE","OpenAI response exceeds the 64 MiB safety bound; use output artifacts.",false);val body=String(bytes,StandardCharsets.UTF_8);if(response.statusCode() !in 200..299)throw ProviderFailure("PROVIDER_ERROR","OpenAI returned HTTP ${response.statusCode()}: ${body.take(1000)}",response.statusCode()==429||response.statusCode()>=500);return body}
    private fun uri(path:String)=URI.create(properties.openAiApiBaseUrl.removeSuffix("/")+path)
}

class ProviderFailure(val code:String,message:String,val retryable:Boolean):IOException(message)
