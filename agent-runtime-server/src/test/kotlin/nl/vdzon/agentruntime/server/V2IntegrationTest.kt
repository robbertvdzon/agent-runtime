package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

@SpringBootTest(properties=["agent-runtime.environment=LOCAL","agent-runtime.object-store-min-free-bytes=0","agent-runtime.test-control-token=local-test-control-token"])
@AutoConfigureMockMvc
class V2IntegrationTest(@Autowired private val mvc:MockMvc,@Autowired private val mapper:ObjectMapper,@Autowired private val jdbc:JdbcTemplate) {
    @Test
    fun `rejected oversized chunk leaves upload resumable at the original offset`() {
        val bytes = "goed".toByteArray()
        val upload = postJson("/v2/uploads", PRODUCT, CreateUploadRequest("retry.txt", "text/plain", bytes.size.toLong(), sha(bytes)), 201)
        val uploadId = upload.path("uploadId").asText()

        mvc.perform(patch("/v2/uploads/$uploadId").bearer(PRODUCT).header("Upload-Offset", 0)
            .contentType("application/offset+octet-stream").content("te-groot"))
            .andExpect(status().isPayloadTooLarge)
        mvc.perform(head("/v2/uploads/$uploadId").bearer(PRODUCT))
            .andExpect(status().isNoContent)
            .andExpect { assertThat(it.response.getHeader("Upload-Offset")).isEqualTo("0") }

        mvc.perform(patch("/v2/uploads/$uploadId").bearer(PRODUCT).header("Upload-Offset", 0)
            .contentType("application/offset+octet-stream").content(bytes))
            .andExpect(status().isNoContent)
        postJson("/v2/uploads/$uploadId/complete", PRODUCT, null, 200)
    }

    @Test
    fun `large text upload is resumable immutable and available to a mock job`() {
        val bytes="# Een groot document\n\nInhoud".toByteArray();val sha=sha(bytes)
        val upload=postJson("/v2/uploads",PRODUCT,CreateUploadRequest("source.md","text/markdown",bytes.size.toLong(),sha),201)
        val uploadId=upload.path("uploadId").asText()
        mvc.perform(patch("/v2/uploads/$uploadId").bearer(PRODUCT).header("Upload-Offset",0).contentType("application/offset+octet-stream").content(bytes.copyOfRange(0,8))).andExpect(status().isNoContent).andExpect{assertThat(it.response.getHeader("Upload-Offset")).isEqualTo("8")}
        mvc.perform(head("/v2/uploads/$uploadId").bearer(PRODUCT)).andExpect(status().isNoContent).andExpect{assertThat(it.response.getHeader("Upload-Offset")).isEqualTo("8")}
        mvc.perform(patch("/v2/uploads/$uploadId").bearer(PRODUCT).header("Upload-Offset",8).contentType("application/offset+octet-stream").content(bytes.copyOfRange(8,bytes.size))).andExpect(status().isNoContent)
        val objectView=postJson("/v2/uploads/$uploadId/complete",PRODUCT,null,200)
        val request=jobRequest(ExecutionSelection("mock","mock",ExecutionMode.MOCK),listOf(InputObjectRef(objectView.path("objectId").asText(),"source",InputRole.SOURCE)))
        postJson("/v2/test-control/mocks",TEST_CONTROL,CreateMockFixtureRequest("product-factory",request.idempotencyKey,result=mapper.readTree("""{"text":"mock"}""")),200)
        val job=postJson("/v2/jobs",PRODUCT,request,202);val result=awaitResult(job.path("id").asText())
        assertThat(result.path("result").path("text").asText()).isEqualTo("mock")
        val download=mvc.perform(get("/v2/jobs/${job.path("id").asText()}/objects/${objectView.path("objectId").asText()}/content").bearer(PRODUCT).header("Range","bytes=2-7")).andExpect(status().isPartialContent).andReturn().response
        assertThat(download.contentAsByteArray).isEqualTo(bytes.copyOfRange(2,8))
    }

    @Test
    fun `worker is fenced and reports usage costs and visible logs`() {
        val model="test-${UUID.randomUUID()}";val selection=ExecutionSelection("openai",model,ExecutionMode.SUBSCRIPTION)
        val baseRequest=jobRequest(selection);val job=postJson("/v2/jobs",PRODUCT,baseRequest.copy(output=baseRequest.output.copy(artifacts=listOf(OutputArtifactDeclaration("evidence",true,setOf("text/plain"),1024)))),202);val jobId=job.path("id").asText();val worker="worker-${UUID.randomUUID()}";val boot=UUID.randomUUID().toString()
        val capability=ExecutorCapability("openai",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION))
        postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(capability)),200)
        val claim=postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0),200);val attempt=claim.path("attempt").path("id").asText();val fence=claim.path("fencingToken").asText()
        postJson("/v2/management/prices",ADMIN,CreatePriceRateRequest("openai",model,ExecutionMode.SUBSCRIPTION,TaskType.STRUCTURED_GENERATION,UsageMetric.OUTPUT_TOKENS,"1000","2.50","EUR",Instant.now().minusSeconds(60),sourceReference="test"),201)
        postJson("/v2/management/subscriptions",ADMIN,CreateSubscriptionPeriodRequest("openai",model,LocalDate.now().minusDays(1),LocalDate.now().plusDays(1),"100","EUR",AllocationMethod.WEIGHTED_TOKENS),201)
        postJson("/v2/workers/$worker/jobs/$jobId/attempts/$attempt/usage-events",WORKER,AppendUsageRequest(fence,"usage-1",Instant.now(),listOf(UsageMetricValue(UsageMetric.OUTPUT_TOKENS,"400",UsageUnit.TOKEN)),source=UsageSource.PROVIDER_REPORTED),204)
        postJson("/v2/workers/$worker/jobs/$jobId/attempts/$attempt/logs",WORKER,AppendLogRequest(fence,"log-1",LogKind.REASONING_SUMMARY,"Ik controleer de bron.","reasoning",true,Instant.now()),204)
        val artifact="bewijs".toByteArray();val outputUpload=postJson("/v2/workers/$worker/jobs/$jobId/output-objects",WORKER,CreateOutputUploadRequest(attempt,fence,"evidence","evidence.txt","text/plain",artifact.size.toLong(),sha(artifact)),201)
        mvc.perform(patch("/v2/workers/$worker/jobs/$jobId/output-objects/${outputUpload.path("uploadId").asText()}").bearer(WORKER).header("X-Attempt-Id",attempt).header("X-Fencing-Token",fence).header("Upload-Offset",0).contentType("application/offset+octet-stream").content(artifact)).andExpect(status().isNoContent)
        val outputObject=postJson("/v2/workers/$worker/jobs/$jobId/output-objects/${outputUpload.path("uploadId").asText()}/complete",WORKER,AttemptAuth(attempt,fence),200)
        postJson("/v2/workers/$worker/jobs/$jobId/attempts/$attempt/result",WORKER,SubmitResultRequest(fence,mapper.readTree("""{"text":"done"}"""),setOf(outputObject.path("objectId").asText())),204)
        val result=getJson("/v2/jobs/$jobId/result",PRODUCT)
        assertThat(result.path("artifacts").first().path("name").asText()).isEqualTo("evidence")
        assertThat(result.path("usageSummary").path("metrics").first().path("quantity").asText()).isEqualTo("400")
        assertThat(result.path("usageSummary").path("costs").any{it.path("kind").asText()=="CALCULATED"&&it.path("amount").asText()=="1"}).isTrue()
        assertThat(result.path("usageSummary").path("costs").any{it.path("kind").asText()=="ALLOCATED"&&it.path("amount").asText()=="100"}).isTrue()
        val events=getJson("/v2/jobs/$jobId/events",PRODUCT).path("items")
        assertThat(events.any{it.path("logKind").asText()=="REASONING_SUMMARY"}).isTrue()
        val summary=getJson("/v2/management/usage/summary?groupBy=TENANT,VENDOR,MODEL",ADMIN)
        assertThat(summary.path("rows").any{it.path("dimensions").path("model").asText()==model}).isTrue()
    }

    @Test
    fun `consumer catalogs are tenant filtered and based on online v2 workers`() {
        val worker="catalog-${UUID.randomUUID()}";val boot=UUID.randomUUID().toString()
        val openAi=ExecutorCapability("openai","gpt-5.6-sol",ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION))
        val anthropic=ExecutorCapability("anthropic","claude-test",ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION))
        postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(openAi,anthropic),setOf("PVDD__ACCEPTANCE_BASE_URL","HKH__TOKEN")),200)

        val executions=getJson("/v2/execution-options?taskType=STRUCTURED_GENERATION",PVDD)
        assertThat(executions).hasSize(1)
        assertThat(executions.first().path("execution").path("vendorId").asText()).isEqualTo("openai")
        assertThat(executions.first().path("matchingOnlineWorkers").asInt()).isEqualTo(1)
        val keys=getJson("/v2/environment-keys?project=PVDD",PVDD)
        assertThat(keys.map{it.path("name").asText()}).containsExactly("PVDD__ACCEPTANCE_BASE_URL")
        mvc.perform(get("/v2/environment-keys?project=HKH").bearer(PVDD)).andExpect(status().isForbidden)
        jdbc.update("UPDATE runtime_v2_worker SET last_heartbeat_at=? WHERE worker_id=?",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(2),worker)
        val offline=getJson("/v2/execution-options?taskType=STRUCTURED_GENERATION",PVDD)
        assertThat(offline.first().path("available").asBoolean()).isFalse()
        assertThat(offline.first().path("matchingOnlineWorkers").asInt()).isZero()
    }

    @Test
    fun `targeted mock fixture supports correction artifacts and isolated credentials`() {
        val request=jobRequest(ExecutionSelection("mock","mock",ExecutionMode.MOCK)).copy(
            output=OutputContract(
                mapper.readTree("""{"type":"object","required":["text"],"properties":{"text":{"type":"string"}},"additionalProperties":false}"""),
                listOf(OutputArtifactDeclaration("evidence-01",true,setOf("text/plain"),1024)),
            ),
        )
        val fixture=CreateMockFixtureRequest(
            tenantId="product-factory",idempotencyKey=request.idempotencyKey,
            outputSequence=listOf("not-json","{\"text\":\"fixed\"}"),outputArtifactNames=setOf("evidence-01"),
        )
        mvc.perform(post("/v2/test-control/mocks").bearer(ADMIN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(fixture))).andExpect(status().isForbidden)
        postJson("/v2/test-control/mocks",TEST_CONTROL,fixture,200)
        mvc.perform(get("/v2/jobs").bearer(TEST_CONTROL)).andExpect(status().isForbidden)

        val job=postJson("/v2/jobs",PRODUCT,request,202);val jobId=job.path("id").asText();val result=awaitResult(jobId)
        assertThat(result.path("result").path("text").asText()).isEqualTo("fixed")
        assertThat(result.path("artifacts").first().path("name").asText()).isEqualTo("evidence-01")
        assertThat(getJson("/v2/jobs/$jobId/attempts",PRODUCT)).hasSize(2)
    }

    @Test
    fun `mock job without exact fixture fails visibly`() {
        val request=jobRequest(ExecutionSelection("mock","mock",ExecutionMode.MOCK))
        val jobId=postJson("/v2/jobs",PRODUCT,request,202).path("id").asText()
        val failed=awaitTerminal(jobId)
        assertThat(failed.path("status").asText()).isEqualTo("FAILED")
        assertThat(failed.path("errorCode").asText()).isEqualTo("NO_MOCK_RESPONSE_CONFIGURED")
    }

    @Test
    fun `mock error and delay are targeted and execution never falls back`() {
        val delayed=jobRequest(ExecutionSelection("mock","mock",ExecutionMode.MOCK))
        postJson("/v2/test-control/mocks",TEST_CONTROL,CreateMockFixtureRequest("product-factory",delayed.idempotencyKey,errorCode="FIXTURE_FAILURE",errorMessage="safe failure",delayMillis=75),200)
        val started=System.nanoTime();val jobId=postJson("/v2/jobs",PRODUCT,delayed,202).path("id").asText();val failed=awaitTerminal(jobId)
        assertThat((System.nanoTime()-started)/1_000_000).isGreaterThanOrEqualTo(50)
        assertThat(failed.path("errorCode").asText()).isEqualTo("FIXTURE_FAILURE")

        val unsupported=jobRequest(ExecutionSelection("unknown-vendor","unknown-model",ExecutionMode.SUBSCRIPTION))
        val response=mvc.perform(post("/v2/jobs").bearer(PRODUCT).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(unsupported))).andExpect(status().isUnprocessableEntity).andReturn().response
        assertThat(mapper.readTree(response.contentAsString).path("code").asText()).isIn("EXECUTION_NOT_ALLOWED","EXECUTION_NOT_SUPPORTED")
    }

    private fun jobRequest(execution:ExecutionSelection,objects:List<InputObjectRef> = emptyList())=CreateJobRequest(UUID.randomUUID().toString(),JobKind.APPLICATION_WORK,TaskType.STRUCTURED_GENERATION,execution,JobInput("Geef het antwoord als JSON.",objects),OutputContract(mapper.readTree("""{"type":"object","required":["text"],"properties":{"text":{"type":"string"}},"additionalProperties":false}""")))
    private fun postJson(path:String,token:String,body:Any?,expected:Int):JsonNode {val builder=post(path).bearer(token).contentType(MediaType.APPLICATION_JSON);if(body!=null)builder.content(mapper.writeValueAsBytes(body));val response=mvc.perform(builder).andExpect(status().`is`(expected)).andReturn().response;return if(response.contentAsString.isBlank())mapper.createObjectNode() else mapper.readTree(response.contentAsString)}
    private fun getJson(path:String,token:String)=mapper.readTree(mvc.perform(get(path).bearer(token)).andExpect(status().isOk).andReturn().response.contentAsString)
    private fun awaitResult(id:String):JsonNode {repeat(30){val response=mvc.perform(get("/v2/jobs/$id/result").bearer(PRODUCT)).andReturn().response;if(response.status==200)return mapper.readTree(response.contentAsString);Thread.sleep(100)};error("job did not complete")}
    private fun awaitTerminal(id:String):JsonNode {repeat(30){val result=getJson("/v2/jobs/$id",PRODUCT);if(result.path("status").asText() in setOf("SUCCEEDED","FAILED","CANCELLED"))return result;Thread.sleep(100)};error("job did not become terminal")}
    private fun sha(value:ByteArray)=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token:String)=header("Authorization","Bearer $token")
    companion object {const val PRODUCT="local-product-factory-token";const val PVDD="local-pvdd-token";const val WORKER="local-worker-token";const val ADMIN="local-admin-token";const val TEST_CONTROL="local-test-control-token"}
}
