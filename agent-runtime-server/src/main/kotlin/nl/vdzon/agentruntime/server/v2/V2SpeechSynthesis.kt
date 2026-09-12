package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit

data class SpeechSegment(val text:String,val voice:String,val speed:Double?)

/** Server-side SPEECH_SYNTHESIS for vendors `openai` and `elevenlabs`: chunks text, calls the provider per chunk and joins the MP3 parts. */
@Service
class V2SpeechSynthesisExecutor(
    private val properties:RuntimeProperties,private val mapper:ObjectMapper,private val jobs:V2JobStore,private val jobService:V2JobService,
    private val objectStore:V2ObjectStore,private val blobs:FilesystemBlobStore,private val uploadService:V2UploadService,private val usage:V2UsageStore,
) {
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    fun synthesize(job:StoredV2Job,attempt:StoredV2Attempt) {
        val vendor=job.view.execution.vendorId
        val segments=segments(job)
        val limit=if(vendor=="elevenlabs")ELEVENLABS_CHUNK_CHARS else OPENAI_CHUNK_CHARS
        val chunks=segments.flatMap{segment->chunkSpeechText(segment.text,limit).map{segment.copy(text=it)}}
        if(chunks.isEmpty())throw ProviderFailure("INVALID_SYNTHESIS_INPUT","Speech synthesis input contains no text.",false)
        val parts=chunks.mapIndexed{index,chunk->
            if(jobs.find(job.view.id)?.cancelRequested==true)throw ProviderFailure("CANCELLED","Speech synthesis was cancelled.",false)
            jobs.progress(job.view.id,attempt.view.id,"CALLING_PROVIDER",(10+80*index/chunks.size),"Synthesizing chunk ${index+1} of ${chunks.size}.")
            if(vendor=="elevenlabs")elevenLabs(job,chunk) else openAi(job,chunk)
        }
        val audio=if(parts.size==1)parts.single() else concatMp3(parts)
        val declaration=job.request.output.artifacts.single()
        declaration.maxBytes?.let{if(audio.size>it)throw ProviderFailure("OUTPUT_TOO_LARGE","Synthesized audio exceeds the declared maximum.",false)}
        val sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio))
        val reserved=uploadService.reserveOutput(job.view.tenantId,job.view.id,attempt.view.id,declaration.name,"${declaration.name}.mp3","audio/mpeg",audio.size.toLong(),sha)
        uploadService.append(job.view.tenantId,reserved.uploadId,0,ByteArrayInputStream(audio),"OUTPUT")
        val ready=uploadService.complete(job.view.tenantId,reserved.uploadId,"OUTPUT")
        objectStore.link(job.view.id,ready.objectId,"OUTPUT",null,declaration.name,0)
        val characters=chunks.sumOf{it.text.length}.toLong()
        usage.append(job,attempt.view.id,AppendUsageRequest("internal","$vendor-tts-${attempt.view.id}",Instant.now(),listOf(UsageMetricValue(UsageMetric.CHARACTERS,characters.toString(),UsageUnit.CHARACTER)),null,UsageSource.PROVIDER_REPORTED))
        val result=mapper.createObjectNode().put("characters",characters).put("chunks",chunks.size).put("segments",segments.size)
        val fresh=jobs.find(job.view.id)!!;val errors=jobService.validateResult(fresh,result,setOf(ready.objectId))
        if(errors.isNotEmpty()){uploadService.discardAttemptOutputs(job.view.id,attempt.view.id);jobs.rejectOutput(fresh,attempt.view.id,"MODEL_OUTPUT_SCHEMA_INVALID",errors.joinToString("; "){it.message});return}
        jobs.complete(job.view.id,attempt.view.id,result,null,null,usage.attemptSummary(attempt.view.id).usageQuality)
    }

    private fun segments(job:StoredV2Job):List<SpeechSegment> {
        val defaults=job.request.synthesis
        val source=objectStore.inputObjects(job.view.id).firstOrNull{it.first=="text"}?.second
        fun voice(value:String?)=value?.takeIf(String::isNotBlank)?:defaults?.voice?.takeIf(String::isNotBlank)?:throw ProviderFailure("INVALID_SYNTHESIS_INPUT","A voice is required for every segment.",false)
        if(source==null)return listOf(SpeechSegment(job.request.input.instruction,voice(null),defaults?.speed))
        val bytes=blobs.open(source.blobKey).use{it.readAllBytes()}
        if(!source.view.mimeType.startsWith("application/json"))return listOf(SpeechSegment(String(bytes,StandardCharsets.UTF_8),voice(null),defaults?.speed))
        val root=runCatching{mapper.readTree(bytes)}.getOrElse{throw ProviderFailure("INVALID_SYNTHESIS_INPUT","Synthesis JSON input is invalid.",false)}
        return root.path("segments").map{node->SpeechSegment(node.path("text").asText(),voice(node.path("voice").asText(null)),node.path("speed").takeIf(JsonNode::isNumber)?.asDouble()?:defaults?.speed)}.filter{it.text.isNotBlank()}
    }

    private fun openAi(job:StoredV2Job,chunk:SpeechSegment):ByteArray {
        val body=mapper.createObjectNode().put("model",job.view.execution.model).put("voice",chunk.voice).put("input",chunk.text).put("response_format","mp3")
        chunk.speed?.let{body.put("speed",it)}
        val request=HttpRequest.newBuilder(URI.create(properties.openAiApiBaseUrl.removeSuffix("/")+"/audio/speech")).timeout(Duration.ofMinutes(5))
            .header("Authorization","Bearer ${properties.openAiApiKey}").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        return send(request,"OpenAI")
    }

    private fun elevenLabs(job:StoredV2Job,chunk:SpeechSegment):ByteArray {
        val body=mapper.createObjectNode().put("text",chunk.text).put("model_id",job.view.execution.model)
        chunk.speed?.let{body.putObject("voice_settings").put("speed",it.coerceIn(0.7,1.2))}
        val request=HttpRequest.newBuilder(URI.create(properties.elevenLabsBaseUrl.removeSuffix("/")+"/v1/text-to-speech/${chunk.voice}")).timeout(Duration.ofMinutes(5))
            .header("xi-api-key",properties.elevenLabsApiKey).header("Accept","audio/mpeg").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        return send(request,"ElevenLabs")
    }

    private fun send(request:HttpRequest,provider:String):ByteArray {
        val response=http.send(request,HttpResponse.BodyHandlers.ofByteArray())
        if(response.statusCode() !in 200..299)throw ProviderFailure(if(response.statusCode()==429)"PROVIDER_RATE_LIMIT" else "PROVIDER_ERROR","$provider returned HTTP ${response.statusCode()}: ${String(response.body(),StandardCharsets.UTF_8).take(1000)}",response.statusCode()==429||response.statusCode()>=500)
        if(response.body().isEmpty())throw ProviderFailure("PROVIDER_OUTPUT_MISSING","$provider returned no audio.",true)
        return response.body()
    }

    private fun concatMp3(parts:List<ByteArray>):ByteArray {
        val dir=Files.createTempDirectory("ar-tts-")
        try {
            val list=parts.mapIndexed{i,bytes->dir.resolve("part-$i.mp3").also{Files.write(it,bytes)}}
            val listFile=dir.resolve("concat.txt").also{Files.writeString(it,list.joinToString("\n"){"file '${it.fileName}'"})}
            val output=dir.resolve("out.mp3")
            val process=ProcessBuilder(properties.ffmpegBinary,"-nostdin","-y","-f","concat","-safe","0","-i",listFile.toString(),"-c","copy",output.toString()).directory(dir.toFile()).redirectErrorStream(true).start()
            val log=process.inputStream.readAllBytes()
            if(!process.waitFor(10,TimeUnit.MINUTES)){process.destroyForcibly();throw ProviderFailure("AUDIO_CONCAT_FAILED","ffmpeg timed out.",true)}
            if(process.exitValue()!=0||!Files.exists(output))throw ProviderFailure("AUDIO_CONCAT_FAILED","ffmpeg failed: ${String(log).takeLast(500)}",false)
            return Files.readAllBytes(output)
        } finally { dir.toFile().deleteRecursively() }
    }

    companion object { const val OPENAI_CHUNK_CHARS=4000; const val ELEVENLABS_CHUNK_CHARS=4500 }
}

/** Splits text in chunks of at most [limit] characters, preferring paragraph, sentence and word boundaries. */
internal fun chunkSpeechText(text:String,limit:Int):List<String> {
    val chunks=mutableListOf<String>();var rest=text.trim()
    while(rest.length>limit) {
        val window=rest.substring(0,limit)
        val cut=listOf(window.lastIndexOf("\n\n"),Regex("[.!?](\\s)").findAll(window).lastOrNull()?.range?.first?.plus(1)?:-1,window.lastIndexOf(' ')).firstOrNull{it>limit/2}?:limit
        chunks+=rest.substring(0,cut).trim();rest=rest.substring(cut).trim()
    }
    if(rest.isNotBlank())chunks+=rest
    return chunks.filter(String::isNotBlank)
}
