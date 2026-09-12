package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.v2.chunkSpeechText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties=["agent-runtime.environment=LOCAL","agent-runtime.object-store-min-free-bytes=0","agent-runtime.api-executor-concurrency=4"])
@AutoConfigureMockMvc
class V2MediaAndParallelIntegrationTest(@Autowired private val mvc:MockMvc,@Autowired private val mapper:ObjectMapper) {
    @Test
    fun `claim filters keep application jobs away from a repository-only claim`() {
        val model="filter-${UUID.randomUUID()}";val capability=ExecutorCapability("anthropic",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION))
        val job=postJson("/v2/jobs",PNF,structured(ExecutionSelection("anthropic",model,ExecutionMode.SUBSCRIPTION)),202)
        val worker="worker-${UUID.randomUUID()}";val boot=UUID.randomUUID().toString()
        postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(capability),maxConcurrency=4),200)
        postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0,setOf(JobKind.REPOSITORY_WORK)),204)
        postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0,null,setOf(TaskType.TRANSCRIPTION)),204)
        val claim=postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0,setOf(JobKind.APPLICATION_WORK),TaskType.entries.filter{it!=TaskType.TRANSCRIPTION}.toSet()),200)
        assertThat(claim.path("job").path("id").asText()).isEqualTo(job.path("id").asText())
    }

    @Test
    fun `local mode is only valid for transcription with exactly one input`() {
        postJson("/v2/jobs",PNF,structured(ExecutionSelection("local","large-v3-turbo",ExecutionMode.LOCAL)),422)
        val transcription=CreateJobRequest(UUID.randomUUID().toString(),JobKind.APPLICATION_WORK,TaskType.TRANSCRIPTION,ExecutionSelection("local","large-v3-turbo",ExecutionMode.LOCAL),JobInput("Transcribeer."),OutputContract(null,listOf(OutputArtifactDeclaration("transcript",true,setOf("text/plain"),null))),transcription=TranscriptionOptions("nl"))
        assertThat(postJson("/v2/jobs",PNF,transcription,422).path("code").asText()).isEqualTo("INVALID_TRANSCRIPTION_INPUT")
        val bytes="audio".toByteArray();val upload=postJson("/v2/uploads",PNF,CreateUploadRequest("episode.mp3","audio/mpeg",bytes.size.toLong(),sha(bytes)),201)
        mvc.perform(patch("/v2/uploads/${upload.path("uploadId").asText()}").bearer(PNF).header("Upload-Offset",0).contentType("application/offset+octet-stream").content(bytes)).andExpect(status().isNoContent)
        val objectId=postJson("/v2/uploads/${upload.path("uploadId").asText()}/complete",PNF,null,200).path("objectId").asText()
        val accepted=postJson("/v2/jobs",PNF,transcription.copy(input=JobInput("Transcribeer.",listOf(InputObjectRef(objectId,"audio",InputRole.AUDIO)))),202)
        assertThat(accepted.path("execution").path("mode").asText()).isEqualTo("LOCAL")
    }

    @Test
    fun `speech synthesis validates its output and runs against elevenlabs`() {
        postJson("/v2/jobs",PNF,speech("elevenlabs","eleven_multilingual_v2").copy(output=OutputContract(null,listOf(OutputArtifactDeclaration("audio",true,setOf("text/plain"),null)))),422)
        val job=postJson("/v2/jobs",PNF,speech("elevenlabs","eleven_multilingual_v2"),202);val jobId=job.path("id").asText()
        val result=awaitResult(jobId)
        assertThat(result.path("result").path("characters").asInt()).isEqualTo(TEXT.length)
        assertThat(result.path("usageSummary").path("metrics").any{it.path("metric").asText()=="CHARACTERS"}).isTrue()
        val artifact=result.path("artifacts").single()
        val async=mvc.perform(get("/v2/jobs/$jobId/objects/${artifact.path("objectId").asText()}/content").bearer(PNF)).andExpect(request().asyncStarted()).andReturn()
        assertThat(mvc.perform(asyncDispatch(async)).andReturn().response.contentAsByteArray).isEqualTo(AUDIO)
        assertThat(elevenLabsBodies.any{it.contains("eleven_multilingual_v2")&&it.contains("Hallo")}).isTrue()
    }

    @Test
    fun `api executor runs several jobs in parallel and each exactly once`() {
        val before=openAiRequests.get()
        val ids=(1..4).map{postJson("/v2/jobs",PNF,speech("openai","tts-1"),202).path("id").asText()}
        ids.forEach{assertThat(awaitResult(it).path("artifacts")).hasSize(1)}
        assertThat(openAiRequests.get()-before).isEqualTo(4)
        assertThat(maxConcurrentOpenAi.get()).isGreaterThan(1)
    }

    @Test
    fun `speech chunker respects the limit and prefers sentence boundaries`() {
        val text="Dit is zin een. ".repeat(400)
        val chunks=chunkSpeechText(text,4000)
        assertThat(chunks).allSatisfy{assertThat(it.length).isLessThanOrEqualTo(4000)}
        assertThat(chunks.dropLast(1)).allSatisfy{assertThat(it).endsWith(".")}
        assertThat(chunks.joinToString(" ").replace(Regex("\\s+")," ")).isEqualTo(text.trim().replace(Regex("\\s+")," "))
    }

    private fun structured(execution:ExecutionSelection)=CreateJobRequest(UUID.randomUUID().toString(),JobKind.APPLICATION_WORK,TaskType.STRUCTURED_GENERATION,execution,JobInput("Geef JSON."),OutputContract(mapper.readTree("""{"type":"object","required":["text"],"properties":{"text":{"type":"string"}},"additionalProperties":false}""")))
    private fun speech(vendor:String,model:String)=CreateJobRequest(UUID.randomUUID().toString(),JobKind.APPLICATION_WORK,TaskType.SPEECH_SYNTHESIS,ExecutionSelection(vendor,model,ExecutionMode.API),JobInput(TEXT),OutputContract(null,listOf(OutputArtifactDeclaration("audio",true,setOf("audio/mpeg"),null))),executionTimeoutSeconds=120,synthesis=SpeechSynthesisOptions("voice-1",1.1))
    private fun postJson(path:String,token:String,body:Any?,expected:Int):JsonNode {val builder=post(path).header("Authorization","Bearer $token").contentType(MediaType.APPLICATION_JSON);if(body!=null)builder.content(mapper.writeValueAsBytes(body));val response=mvc.perform(builder).andExpect(status().`is`(expected)).andReturn().response;return if(response.contentAsString.isBlank())mapper.createObjectNode() else mapper.readTree(response.contentAsString)}
    private fun awaitResult(id:String):JsonNode {repeat(100){val response=mvc.perform(get("/v2/jobs/$id/result").header("Authorization","Bearer $PNF")).andReturn().response;if(response.status==200)return mapper.readTree(response.contentAsString);Thread.sleep(100)};error("job did not complete: "+mvc.perform(get("/v2/jobs/$id").header("Authorization","Bearer $PNF")).andReturn().response.contentAsString)}
    private fun sha(value:ByteArray)=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value))
    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token:String)=header("Authorization","Bearer $token")

    companion object {
        const val PNF="local-personal-news-feed-token";const val WORKER="local-worker-token";const val TEXT="Hallo, dit is een korte test."
        val AUDIO=byteArrayOf(0x49,0x44,0x33,0x04,0x00,0x01,0x02,0x03)
        val openAiRequests=AtomicInteger();val activeOpenAi=AtomicInteger();val maxConcurrentOpenAi=AtomicInteger();val elevenLabsBodies=CopyOnWriteArrayList<String>()
        private val server:HttpServer=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            executor=java.util.concurrent.Executors.newFixedThreadPool(8)
            createContext("/v1/audio/speech"){exchange->
                openAiRequests.incrementAndGet();val active=activeOpenAi.incrementAndGet();maxConcurrentOpenAi.accumulateAndGet(active,::maxOf)
                exchange.requestBody.readAllBytes();Thread.sleep(400);activeOpenAi.decrementAndGet()
                exchange.sendResponseHeaders(200,AUDIO.size.toLong());exchange.responseBody.use{it.write(AUDIO)}
            }
            createContext("/v1/text-to-speech/"){exchange->
                elevenLabsBodies+=String(exchange.requestBody.readAllBytes())
                exchange.sendResponseHeaders(200,AUDIO.size.toLong());exchange.responseBody.use{it.write(AUDIO)}
            }
            start()
        }
        @JvmStatic @DynamicPropertySource
        fun providers(registry:DynamicPropertyRegistry) {
            registry.add("agent-runtime.open-ai-api-key"){"test-openai-key"}
            registry.add("agent-runtime.open-ai-api-base-url"){"http://127.0.0.1:${server.address.port}/v1"}
            registry.add("agent-runtime.eleven-labs-api-key"){"test-elevenlabs-key"}
            registry.add("agent-runtime.eleven-labs-base-url"){"http://127.0.0.1:${server.address.port}"}
        }
    }
}
