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
    fun `built in API price catalog covers current subscription models`() {
        val openAi = getJson("/v2/management/prices?vendorId=openai&model=gpt-5.6-sol", ADMIN)
        assertThat(openAi.any {
                it.path("mode").asText() == "API" &&
                it.path("taskType").asText() == "STRUCTURED_GENERATION" &&
                it.path("metric").asText() == "INPUT_TOKENS" &&
                java.math.BigDecimal(it.path("unitPrice").asText()).compareTo(java.math.BigDecimal("4.00")) == 0
        }).isTrue()
        assertThat(openAi.any {
            it.path("taskType").asText() == "REPOSITORY_AGENT" &&
                it.path("metric").asText() == "OUTPUT_TOKENS" &&
                java.math.BigDecimal(it.path("unitPrice").asText()).compareTo(java.math.BigDecimal("20.00")) == 0
        }).isTrue()

        val anthropic = getJson("/v2/management/prices?vendorId=anthropic&model=claude-sonnet-5", ADMIN)
        assertThat(anthropic.any {
            it.path("mode").asText() == "API" &&
                it.path("metric").asText() == "OUTPUT_TOKENS" &&
                java.math.BigDecimal(it.path("unitPrice").asText()).compareTo(java.math.BigDecimal("10.00")) == 0
        }).isTrue()
    }

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
        val queued=getJson("/v2/management/queue",ADMIN).path("items").first{it.path("id").asText()==jobId}
        assertThat(queued.path("application").asText()).isEqualTo("product-factory")
        assertThat(queued.path("provider").asText()).isEqualTo("openai")
        val capability=ExecutorCapability("openai",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.STRUCTURED_GENERATION))
        postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(capability)),200)
        val claim=postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0),200);val attempt=claim.path("attempt").path("id").asText();val fence=claim.path("fencingToken").asText()
        assertThat(getJson("/v2/management/jobs/running",ADMIN).path("items").any{it.path("id").asText()==jobId}).isTrue()
        val managementWorker=getJson("/v2/management/workers",ADMIN).path("items").first{it.path("worker").path("workerId").asText()==worker}
        assertThat(managementWorker.path("activeJobs").asInt()).isEqualTo(1)
        assertThat(managementWorker.path("worker").path("providers").map(JsonNode::asText)).contains("openai")
        postJson("/v2/management/prices",ADMIN,CreatePriceRateRequest("openai",model,ExecutionMode.API,TaskType.STRUCTURED_GENERATION,UsageMetric.OUTPUT_TOKENS,"1000","2.50","EUR",Instant.now().minusSeconds(60),sourceReference="test"),201)
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
        assertThat(result.path("usageSummary").path("costs").any{it.path("kind").asText()=="API_EQUIVALENT"&&it.path("amount").asText()=="1"}).isTrue()
        assertThat(result.path("usageSummary").path("costs").any{it.path("kind").asText()=="ALLOCATED"&&it.path("amount").asText()=="100"}).isTrue()
        val completed=getJson("/v2/management/jobs/completed?consumer=product-factory&title=${jobId.take(8)}",ADMIN)
        val completedItem=completed.path("items").single()
        assertThat(completedItem.path("id").asText()).isEqualTo(jobId)
        assertThat(completedItem.path("costAvailable").asBoolean()).isTrue()
        assertThat(completedItem.path("artifactCount").asInt()).isEqualTo(1)
        val detail=getJson("/v2/management/jobs/$jobId",ADMIN)
        assertThat(detail.path("result").path("result").path("text").asText()).isEqualTo("done")
        assertThat(detail.path("attempts")).hasSize(1)
        assertThat(detail.path("result").path("artifacts").single().path("id").asText()).isEqualTo(outputObject.path("objectId").asText())
        val downloadRequest=mvc.perform(get("/v2/management/jobs/$jobId/artifacts/${outputObject.path("objectId").asText()}").bearer(ADMIN))
            .andExpect(status().isOk).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn()
        val downloaded=mvc.perform(asyncDispatch(downloadRequest)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(downloaded).isEqualTo(artifact)
        val transcript=getJson("/v2/management/jobs/$jobId/transcript",ADMIN)
        assertThat(transcript.path("items").any{it.path("kind").asText()=="REASONING_SUMMARY"&&it.path("text").asText()=="Ik controleer de bron."}).isTrue()
        val events=getJson("/v2/jobs/$jobId/events",PRODUCT).path("items")
        assertThat(events.any{it.path("logKind").asText()=="REASONING_SUMMARY"}).isTrue()
        val summary=getJson("/v2/management/usage/summary?groupBy=TENANT,VENDOR,MODEL",ADMIN)
        assertThat(summary.path("rows").any{it.path("dimensions").path("model").asText()==model}).isTrue()
        val overview=getJson("/v2/management/consumers",ADMIN)
        val productFactory=overview.path("items").first{it.path("consumer").asText()=="product-factory"}
        assertThat(productFactory.path("models").any{it.path("model").asText()==model&&it.path("mode").asText()=="SUBSCRIPTION"}).isTrue()
        assertThat(productFactory.path("costsInPeriod").any{it.path("kind").asText()=="API_EQUIVALENT"&&it.path("amount").asText()=="1"}).isTrue()
        assertThat(productFactory.path("costsInPeriod").any{it.path("kind").asText()=="ALLOCATED"&&it.path("amount").asText()=="100"}).isTrue()
        mvc.perform(get("/v2/management/consumers").bearer(ADMIN))
            .andExpect(status().isOk)
            .andExpect { assertThat(it.response.getHeader("Cache-Control")).contains("no-store").doesNotContain("immutable") }
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

    @Test
    fun `repository checkout validation matrix and alias policy are enforced`() {
        val schema=OutputContract(mapper.readTree("""{"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}"""))
        fun request(kind:JobKind,task:TaskType,checkout:RepositoryCheckout?,snapshot:RepositorySnapshot?=null)=CreateJobRequest(
            UUID.randomUUID().toString(),kind,task,ExecutionSelection("mock","mock",ExecutionMode.MOCK),JobInput("do it"),schema,snapshot,checkout,
        )
        val branch=RepositoryCheckout("test-repository","software-factory/SF-123",RepositoryPublicationMode.COMMIT_AND_PUSH)
        postJson("/v2/jobs",SOFTWARE,request(JobKind.REPOSITORY_WORK,TaskType.REPOSITORY_AGENT,null),400)
        postJson("/v2/jobs",SOFTWARE,request(JobKind.REPOSITORY_WORK,TaskType.REPOSITORY_AGENT,branch.copy(publicationMode=RepositoryPublicationMode.NONE)),400)
        postJson("/v2/jobs",SOFTWARE,request(JobKind.APPLICATION_WORK,TaskType.REPOSITORY_AGENT,branch),400)
        postJson("/v2/jobs",SOFTWARE,request(JobKind.APPLICATION_WORK,TaskType.REPOSITORY_AGENT,branch.copy(publicationMode=RepositoryPublicationMode.NONE),RepositorySnapshot("https://example.invalid/repo.git","1".repeat(40))),400)
        postJson("/v2/jobs",SOFTWARE,request(JobKind.REPOSITORY_WORK,TaskType.REPOSITORY_AGENT,branch.copy(branch="bad..branch")),400)
        postJson("/v2/jobs",SOFTWARE,request(JobKind.REPOSITORY_WORK,TaskType.REPOSITORY_AGENT,branch.copy(alias="not-allowed")),403)
        postJson("/v2/jobs",PRODUCT,request(JobKind.REPOSITORY_WORK,TaskType.REPOSITORY_AGENT,branch),403)

        val readOnly=request(JobKind.APPLICATION_WORK,TaskType.REPOSITORY_AGENT,branch.copy(publicationMode=RepositoryPublicationMode.NONE))
        postJson("/v2/test-control/mocks",TEST_CONTROL,CreateMockFixtureRequest("software-factory",readOnly.idempotencyKey,result=mapper.readTree("""{"text":"reviewed"}""")),200)
        val readOnlyId=postJson("/v2/jobs",SOFTWARE,readOnly,202).path("id").asText()
        val result=awaitResult(readOnlyId,SOFTWARE)
        assertThat(result.path("repositoryResult").path("publicationStatus").asText()).isEqualTo("NONE")
        assertThat(result.path("repositoryResult").path("branch").asText()).isEqualTo("software-factory/SF-123")

        val snapshot=request(JobKind.APPLICATION_WORK,TaskType.STRUCTURED_GENERATION,null,RepositorySnapshot("https://example.invalid/repo.git","1".repeat(40)))
            .copy(execution=ExecutionSelection("openai","snapshot-${UUID.randomUUID()}",ExecutionMode.SUBSCRIPTION))
        postJson("/v2/jobs",SOFTWARE,snapshot,202)
    }

    @Test
    fun `worker selection requires advertised repository alias and catalog exposes only names`() {
        val model="repo-${UUID.randomUUID()}";val capability=ExecutorCapability("openai",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.REPOSITORY_AGENT))
        val worker="repo-worker-${UUID.randomUUID()}";val boot=UUID.randomUUID().toString()
        val request=repositoryJob(model)
        val jobId=postJson("/v2/jobs",SOFTWARE,request,202).path("id").asText()
        postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(capability)),200)
        mvc.perform(post("/v2/workers/$worker/claims").bearer(WORKER).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(ClaimRequest(boot,setOf(capability),0)))).andExpect(status().isNoContent)
        val registration=postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(capability),availableRepositoryAliases=setOf("test-repository")),200)
        assertThat(registration.path("availableRepositoryAliases").map(JsonNode::asText)).containsExactly("test-repository")
        val aliases=getJson("/v2/repository-aliases",SOFTWARE)
        assertThat(aliases.first{it.path("alias").asText()=="test-repository"}.path("matchingOnlineWorkers").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(aliases.toString()).doesNotContain("http", "github")
        val claim=postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0),200)
        assertThat(claim.path("job").path("id").asText()).isEqualTo(jobId)
    }

    @Test
    fun `validated repository publication is durable and can be confirmed by a recovery attempt`() {
        val model="publication-${UUID.randomUUID()}";val capability=ExecutorCapability("openai",model,ExecutionMode.SUBSCRIPTION,setOf(TaskType.REPOSITORY_AGENT))
        val worker="publication-worker-${UUID.randomUUID()}";val boot=UUID.randomUUID().toString()
        postJson("/v2/workers/register",WORKER,WorkerRegistrationRequest(worker,boot,setOf(capability),availableRepositoryAliases=setOf("test-repository")),200)
        val request=repositoryJob(model);val jobId=postJson("/v2/jobs",SOFTWARE,request,202).path("id").asText()
        val first=postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0),200)
        val firstAttempt=first.path("attempt").path("id").asText();val firstFence=first.path("fencingToken").asText()
        val checkoutSha="1".repeat(40);val commitSha="2".repeat(40);val result=mapper.readTree("""{"text":"implemented"}""")
        val repositoryResult=RepositoryResult("test-repository","software-factory/SF-123",checkoutSha,RepositoryPublicationStatus.PUSHED,commitSha,"1 file changed")
        val prepared=postJson("/v2/workers/$worker/jobs/$jobId/attempts/$firstAttempt/repository-publication",WORKER,PrepareRepositoryPublicationRequest(firstFence,result,emptySet(),repositoryResult),200)
        assertThat(prepared.path("status").asText()).isEqualTo("PREPARED")
        jdbc.update("UPDATE runtime_v2_job SET max_attempts=1 WHERE id=?",jobId)
        postJson("/v2/workers/$worker/jobs/$jobId/attempts/$firstAttempt/fail",WORKER,FailAttemptRequest(firstFence,"GIT_PUSH_FAILED","response uncertain",true),204)
        jdbc.update("UPDATE runtime_v2_job SET not_before=CURRENT_TIMESTAMP WHERE id=?",jobId)
        val second=postJson("/v2/workers/$worker/claims",WORKER,ClaimRequest(boot,setOf(capability),0),200)
        assertThat(second.path("repositoryPublication").path("intendedCommitSha").asText()).isEqualTo(commitSha)
        assertThat(second.path("attempt").path("number").asInt()).isEqualTo(2)
        assertThat(second.path("job").path("maxAttempts").asInt()).isEqualTo(1)
        val secondAttempt=second.path("attempt").path("id").asText();val secondFence=second.path("fencingToken").asText()
        postJson("/v2/workers/$worker/jobs/$jobId/attempts/$secondAttempt/repository-publication/confirm",WORKER,ConfirmRepositoryPublicationRequest(secondFence,commitSha),204)
        val publicResult=getJson("/v2/jobs/$jobId/result",SOFTWARE)
        assertThat(publicResult.path("result")).isEqualTo(result)
        assertThat(publicResult.path("repositoryResult").path("commitSha").asText()).isEqualTo(commitSha)
        assertThat(publicResult.path("repositoryResult").path("diffStat").asText()).isEqualTo("1 file changed")
        assertThat(getJson("/v2/jobs/$jobId/attempts",SOFTWARE)).hasSize(2)
        val management=getJson("/v2/management/jobs/$jobId",ADMIN)
        assertThat(management.path("job").path("repositoryAlias").asText()).isEqualTo("test-repository")
        assertThat(management.path("job").path("repositoryPublicationStatus").asText()).isEqualTo("PUSHED")
        assertThat(management.path("result").path("repositoryResult").path("checkoutCommitSha").asText()).isEqualTo(checkoutSha)
        val managementWorkers=getJson("/v2/management/workers",ADMIN).path("items")
        val registeredWorker=managementWorkers.first { it.path("worker").path("workerId").asText()==worker }
        assertThat(registeredWorker.path("worker").path("availableRepositoryAliases").map(JsonNode::asText)).contains("test-repository")
    }

    @Test
    fun `mock repository work can simulate pushed repository metadata`() {
        val request=repositoryJob("mock").copy(execution=ExecutionSelection("mock","mock",ExecutionMode.MOCK))
        val repositoryResult=RepositoryResult("test-repository","software-factory/SF-123","a".repeat(40),RepositoryPublicationStatus.PUSHED,"b".repeat(40),"2 files changed")
        postJson("/v2/test-control/mocks",TEST_CONTROL,CreateMockFixtureRequest("software-factory",request.idempotencyKey,result=mapper.readTree("""{"text":"mocked"}"""),repositoryResult=repositoryResult),200)
        val jobId=postJson("/v2/jobs",SOFTWARE,request,202).path("id").asText()
        val result=awaitResult(jobId,SOFTWARE)
        assertThat(result.path("repositoryResult").path("publicationStatus").asText()).isEqualTo("PUSHED")
        assertThat(result.path("repositoryResult").path("commitSha").asText()).isEqualTo("b".repeat(40))
    }

    @Test
    fun `repository verification is validated and direct green is visible`() {
        val tooShort = verifiedRepositoryJob().copy(executionTimeoutSeconds = 599)
        postJson("/v2/jobs", SOFTWARE, tooShort, 400)

        val request = verifiedRepositoryJob()
        postJson("/v2/test-control/mocks", TEST_CONTROL, CreateMockFixtureRequest("software-factory", request.idempotencyKey, result = mapper.readTree("""{"text":"green"}""")), 200)
        val jobId = postJson("/v2/jobs", SOFTWARE, request, 202).path("id").asText()
        val result = awaitResult(jobId, SOFTWARE)
        assertThat(result.path("verificationResult").path("status").asText()).isEqualTo("PASSED")
        assertThat(result.path("verificationResult").path("agentRounds").asInt()).isEqualTo(1)
        assertThat(result.path("repositoryResult").path("publicationStatus").asText()).isEqualTo("PUSHED")
        val item = getJson("/v2/management/jobs/$jobId", ADMIN).path("job")
        assertThat(item.path("verificationStatus").asText()).isEqualTo("PASSED")
        assertThat(item.path("verificationAgentRounds").asInt()).isEqualTo(1)
    }

    @Test
    fun `mock verification can model repair no changes and permanent red atomically`() {
        val repaired = verifiedRepositoryJob()
        val repairedResult = VerificationResult(
            VerificationStatus.PASSED, 1, 2,
            listOf(VerificationCommandResult("verify", listOf("mvn", "verify"), VerificationCommandStatus.PASSED, 0, 25, "BUILD SUCCESS")),
        )
        postJson("/v2/test-control/mocks", TEST_CONTROL, CreateMockFixtureRequest("software-factory", repaired.idempotencyKey, result = mapper.readTree("""{"text":"repaired"}"""), verificationResult = repairedResult), 200)
        val repairedId = postJson("/v2/jobs", SOFTWARE, repaired, 202).path("id").asText()
        assertThat(awaitResult(repairedId, SOFTWARE).path("verificationResult").path("agentRounds").asInt()).isEqualTo(2)

        val noChanges = verifiedRepositoryJob()
        val checkout = requireNotNull(noChanges.repositoryCheckout)
        val repositoryResult = RepositoryResult(checkout.alias, checkout.branch, "a".repeat(40), RepositoryPublicationStatus.NO_CHANGES)
        postJson("/v2/test-control/mocks", TEST_CONTROL, CreateMockFixtureRequest("software-factory", noChanges.idempotencyKey, result = mapper.readTree("""{"text":"unchanged"}"""), repositoryResult = repositoryResult), 200)
        val noChangesId = postJson("/v2/jobs", SOFTWARE, noChanges, 202).path("id").asText()
        val noChangesResult = awaitResult(noChangesId, SOFTWARE)
        assertThat(noChangesResult.hasNonNull("verificationResult")).isFalse()
        assertThat(noChangesResult.path("repositoryResult").path("publicationStatus").asText()).isEqualTo("NO_CHANGES")

        val red = verifiedRepositoryJob(maxRepairAttempts = 0)
        val failedResult = VerificationResult(
            VerificationStatus.FAILED, 1, 1,
            listOf(VerificationCommandResult("verify", listOf("mvn", "verify"), VerificationCommandStatus.FAILED, 1, 40, "redacted failure")),
        )
        postJson("/v2/test-control/mocks", TEST_CONTROL, CreateMockFixtureRequest("software-factory", red.idempotencyKey, result = mapper.readTree("""{"text":"validated despite red tests"}"""), verificationResult = failedResult), 200)
        val redId = postJson("/v2/jobs", SOFTWARE, red, 202).path("id").asText()
        val terminal = awaitTerminal(redId, SOFTWARE)
        assertThat(terminal.path("status").asText()).isEqualTo("FAILED")
        assertThat(terminal.path("errorCode").asText()).isEqualTo("VERIFICATION_FAILED")
        val redResult = getJson("/v2/jobs/$redId/result", SOFTWARE)
        assertThat(redResult.path("result").path("text").asText()).isEqualTo("validated despite red tests")
        assertThat(redResult.path("verificationResult").path("commands").first().path("outputTail").asText()).isEqualTo("redacted failure")
        assertThat(redResult.hasNonNull("repositoryResult")).isFalse()
        assertThat(getJson("/v2/jobs/$redId/attempts", SOFTWARE)).hasSize(1)
    }

    @Test
    fun `configuration and timeout verification failures retain a readable result`() {
        listOf(
            VerificationResult(VerificationStatus.CONFIG_MISSING, null, 1) to "VERIFICATION_CONFIG_MISSING",
            VerificationResult(VerificationStatus.CONFIG_INVALID, null, 1) to "VERIFICATION_CONFIG_INVALID",
            VerificationResult(VerificationStatus.TIMEOUT, 1, 2) to "VERIFICATION_TIMEOUT",
        ).forEach { (verification, expectedCode) ->
            val request = verifiedRepositoryJob()
            postJson("/v2/test-control/mocks", TEST_CONTROL, CreateMockFixtureRequest("software-factory", request.idempotencyKey, result = mapper.readTree("""{"text":"kept"}"""), verificationResult = verification), 200)
            val jobId = postJson("/v2/jobs", SOFTWARE, request, 202).path("id").asText()
            assertThat(awaitTerminal(jobId, SOFTWARE).path("errorCode").asText()).isEqualTo(expectedCode)
            assertThat(getJson("/v2/jobs/$jobId/result", SOFTWARE).path("verificationResult").path("status").asText()).isEqualTo(verification.status.name)
        }
    }

    private fun jobRequest(execution:ExecutionSelection,objects:List<InputObjectRef> = emptyList())=CreateJobRequest(UUID.randomUUID().toString(),JobKind.APPLICATION_WORK,TaskType.STRUCTURED_GENERATION,execution,JobInput("Geef het antwoord als JSON.",objects),OutputContract(mapper.readTree("""{"type":"object","required":["text"],"properties":{"text":{"type":"string"}},"additionalProperties":false}""")))
    private fun repositoryJob(model:String)=CreateJobRequest(UUID.randomUUID().toString(),JobKind.REPOSITORY_WORK,TaskType.REPOSITORY_AGENT,ExecutionSelection("openai",model,ExecutionMode.SUBSCRIPTION),JobInput("Werk de story uit."),OutputContract(mapper.readTree("""{"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}""")),repositoryCheckout=RepositoryCheckout("test-repository","software-factory/SF-123",RepositoryPublicationMode.COMMIT_AND_PUSH))
    private fun verifiedRepositoryJob(maxRepairAttempts:Int=3)=repositoryJob("mock").copy(execution=ExecutionSelection("mock","mock",ExecutionMode.MOCK),verification=JobVerification(VerificationMode.REPOSITORY_CONFIG,maxRepairAttempts),executionTimeoutSeconds=600)
    private fun postJson(path:String,token:String,body:Any?,expected:Int):JsonNode {val builder=post(path).bearer(token).contentType(MediaType.APPLICATION_JSON);if(body!=null)builder.content(mapper.writeValueAsBytes(body));val response=mvc.perform(builder).andExpect(status().`is`(expected)).andReturn().response;return if(response.contentAsString.isBlank())mapper.createObjectNode() else mapper.readTree(response.contentAsString)}
    private fun getJson(path:String,token:String)=mapper.readTree(mvc.perform(get(path).bearer(token)).andExpect(status().isOk).andReturn().response.contentAsString)
    private fun awaitResult(id:String,token:String=PRODUCT):JsonNode {repeat(30){val response=mvc.perform(get("/v2/jobs/$id/result").bearer(token)).andReturn().response;if(response.status==200)return mapper.readTree(response.contentAsString);Thread.sleep(100)};error("job did not complete")}
    private fun awaitTerminal(id:String,token:String=PRODUCT):JsonNode {repeat(30){val result=getJson("/v2/jobs/$id",token);if(result.path("status").asText() in setOf("SUCCEEDED","FAILED","CANCELLED"))return result;Thread.sleep(100)};error("job did not become terminal")}
    private fun sha(value:ByteArray)=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token:String)=header("Authorization","Bearer $token")
    companion object {const val PRODUCT="local-product-factory-token";const val SOFTWARE="local-software-factory-token";const val PVDD="local-pvdd-token";const val WORKER="local-worker-token";const val ADMIN="local-admin-token";const val TEST_CONTROL="local-test-control-token"}
}
