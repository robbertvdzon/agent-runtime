package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeEnvironment
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import nl.vdzon.agentruntime.server.jobs.JsonResultValidator
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class StoredV2Job(
    val view: JobView,
    val request: CreateJobRequest,
    val result: JsonNode?,
    val repositoryResult: RepositoryResult?,
    val verificationResult: VerificationResult?,
    val cancelRequested: Boolean,
)
data class StoredV2Attempt(
    val view: AttemptView, val workerId: String, val workerBootId: String, val fencingTokenHash: String,
    val leaseUntil: Instant, val attemptDeadline: Instant,
)

@Repository
class V2JobStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun insert(tenantId: String, request: CreateJobRequest, maxAttempts: Int): StoredV2Job {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        jdbc.update(
            """INSERT INTO runtime_v2_job(id,tenant_id,idempotency_key,job_kind,task_type,vendor_id,model,execution_mode,request_json,status,phase,max_attempts,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,'QUEUED','QUEUED',?,?,?)""",
            id,tenantId,request.idempotencyKey,request.jobKind.name,request.taskType.name,request.execution.vendorId,request.execution.model,
            request.execution.mode.name,mapper.writeValueAsString(request),maxAttempts,utc(now),utc(now),
        )
        addEvent(id,null,EventType.JOB_STATUS_CHANGED,"QUEUED","Job accepted.",status=JobStatus.QUEUED)
        return find(id)!!
    }

    fun find(id: String): StoredV2Job? = jdbc.query("SELECT * FROM runtime_v2_job WHERE id=?", rowMapper(), id).firstOrNull()
    fun find(tenantId: String, id: String): StoredV2Job? = jdbc.query("SELECT * FROM runtime_v2_job WHERE id=? AND tenant_id=?", rowMapper(), id, tenantId).firstOrNull()
    fun findByIdempotency(tenantId: String, key: String): StoredV2Job? = jdbc.query("SELECT * FROM runtime_v2_job WHERE tenant_id=? AND idempotency_key=?",rowMapper(),tenantId,key).firstOrNull()
    fun list(tenantId: String?, status: JobStatus?, cursor: String?, limit: Int): List<StoredV2Job> {
        val conditions = mutableListOf<String>(); val args = mutableListOf<Any>()
        tenantId?.let { conditions += "tenant_id=?"; args += it }
        status?.let { conditions += "status=?"; args += it.name }
        cursor?.let { conditions += "created_at < (SELECT created_at FROM runtime_v2_job WHERE id=?)"; args += it }
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        args += limit.coerceIn(1,100)
        return jdbc.query("SELECT * FROM runtime_v2_job$where ORDER BY created_at DESC,id DESC LIMIT ?",rowMapper(),*args.toTypedArray())
    }

    fun managementList(): List<StoredV2Job> =
        jdbc.query("SELECT * FROM runtime_v2_job ORDER BY created_at DESC,id DESC LIMIT 5000", rowMapper())

    fun queued(): List<StoredV2Job> = jdbc.query("""SELECT * FROM runtime_v2_job WHERE status IN ('QUEUED','WAITING_FOR_WORKER')
        AND (not_before IS NULL OR not_before<=CURRENT_TIMESTAMP) ORDER BY created_at,id""",rowMapper())

    fun markWaiting(id: String) { jdbc.update("UPDATE runtime_v2_job SET status='WAITING_FOR_WORKER',phase='WAITING_FOR_WORKER',updated_at=? WHERE id=? AND status='QUEUED'",utc(Instant.now()),id) }

    fun createAttempt(job: StoredV2Job, workerId: String, bootId: String, rawFencingToken: String, leaseUntil: Instant, deadline: Instant): StoredV2Attempt {
        val id = UUID.randomUUID().toString(); val now=Instant.now(); val number=job.view.attemptCount+1
        val reason = if(number==1) AttemptReason.INITIAL else when(job.view.errorCode) {
            "MODEL_OUTPUT_NOT_JSON","MODEL_OUTPUT_SCHEMA_INVALID","MISSING_REQUIRED_ARTIFACT" -> AttemptReason.INVALID_OUTPUT
            "PROVIDER_ERROR","PROVIDER_RATE_LIMIT","ENGINE_FAILED" -> AttemptReason.PROVIDER_ERROR
            else -> AttemptReason.TECHNICAL_ERROR
        }
        jdbc.update("""INSERT INTO runtime_v2_attempt(id,job_id,worker_id,worker_boot_id,attempt_number,reason,status,vendor_id,model,execution_mode,task_type,fencing_token_hash,lease_until,attempt_deadline,heartbeat_at,started_at)
            VALUES (?,?,?,?,?,?,'RUNNING',?,?,?,?,?,?,?,?,?)""",id,job.view.id,workerId,bootId,number,reason.name,job.view.execution.vendorId,job.view.execution.model,job.view.execution.mode.name,job.view.taskType.name,
            hash(rawFencingToken),utc(leaseUntil),utc(deadline),utc(now),utc(now))
        jdbc.update("""UPDATE runtime_v2_job SET status='RUNNING',phase='EXECUTING',attempt_count=?,error_code=NULL,error_message=NULL,progress_percent=0,updated_at=? WHERE id=? AND status IN ('QUEUED','WAITING_FOR_WORKER')""",number,utc(now),job.view.id)
        addEvent(job.view.id,id,EventType.ATTEMPT_STARTED,"EXECUTING","Attempt $number started.",status=JobStatus.RUNNING,percent=0)
        return attempt(id)!!
    }

    fun attempt(id: String): StoredV2Attempt? = jdbc.query("SELECT * FROM runtime_v2_attempt WHERE id=?",attemptRowMapper(),id).firstOrNull()
    fun attempts(jobId: String): List<StoredV2Attempt> = jdbc.query("SELECT * FROM runtime_v2_attempt WHERE job_id=? ORDER BY attempt_number",attemptRowMapper(),jobId)
    fun activeAttempt(jobId: String): StoredV2Attempt? = jdbc.query(
        "SELECT * FROM runtime_v2_attempt WHERE job_id=? AND status='RUNNING' ORDER BY attempt_number DESC LIMIT 1",
        attemptRowMapper(), jobId,
    ).firstOrNull()
    fun expiredAttempts(cutoff:Instant):List<StoredV2Attempt> = jdbc.query("SELECT * FROM runtime_v2_attempt WHERE status='RUNNING' AND (lease_until<? OR attempt_deadline<CURRENT_TIMESTAMP)",attemptRowMapper(),utc(cutoff))

    fun extendLease(attemptId: String, lease: Instant) { jdbc.update("UPDATE runtime_v2_attempt SET heartbeat_at=?,lease_until=? WHERE id=? AND status='RUNNING'",utc(Instant.now()),utc(lease),attemptId) }
    fun progress(jobId:String,attemptId:String,phase:String,percent:Int?,message:String?) {
        jdbc.update("UPDATE runtime_v2_job SET phase=?,progress_percent=?,progress_message=?,updated_at=? WHERE id=? AND status='RUNNING'",phase.take(80),percent,message?.take(1000),utc(Instant.now()),jobId)
        addEvent(jobId,attemptId,EventType.PROGRESS_UPDATED,phase,message,percent=percent)
    }

    fun complete(jobId:String,attemptId:String,result:JsonNode,repositoryResult:RepositoryResult?,verificationResult:VerificationResult?,quality:UsageQuality) {
        val now=Instant.now()
        jdbc.update("UPDATE runtime_v2_attempt SET status='SUCCEEDED',usage_quality=?,completed_at=? WHERE id=? AND status='RUNNING'",quality.name,utc(now),attemptId)
        jdbc.update("UPDATE runtime_v2_job SET status='SUCCEEDED',phase='COMPLETED',progress_percent=100,result_json=?,repository_result_json=?,verification_result_json=?,completed_at=?,updated_at=? WHERE id=? AND status='RUNNING'",result.toString(),repositoryResult?.let(mapper::writeValueAsString),verificationResult?.let(mapper::writeValueAsString),utc(now),utc(now),jobId)
        addEvent(jobId,attemptId,EventType.ATTEMPT_FINISHED,"COMPLETED","Attempt succeeded.",status=JobStatus.SUCCEEDED,percent=100)
        addEvent(jobId,attemptId,EventType.JOB_FINISHED,"COMPLETED","Job completed successfully.",status=JobStatus.SUCCEEDED,percent=100)
    }

    fun completeWithVerificationFailure(jobId:String,attemptId:String,result:JsonNode,verificationResult:VerificationResult,quality:UsageQuality,code:String,message:String) {
        val now=Instant.now()
        jdbc.update("UPDATE runtime_v2_attempt SET status='FAILED',usage_quality=?,error_code=?,completed_at=? WHERE id=? AND status='RUNNING'",quality.name,code.take(120),utc(now),attemptId)
        jdbc.update("UPDATE runtime_v2_job SET status='FAILED',phase='VERIFICATION_FAILED',progress_percent=100,result_json=?,repository_result_json=NULL,verification_result_json=?,error_code=?,error_message=?,completed_at=?,updated_at=? WHERE id=? AND status='RUNNING'",result.toString(),mapper.writeValueAsString(verificationResult),code.take(120),message.take(2000),utc(now),utc(now),jobId)
        addEvent(jobId,attemptId,EventType.ATTEMPT_FINISHED,"VERIFICATION_FAILED","$code: ${message.take(800)}",status=JobStatus.FAILED,percent=100)
        addEvent(jobId,attemptId,EventType.JOB_FINISHED,"VERIFICATION_FAILED","Job failed verification: $code.",status=JobStatus.FAILED,percent=100)
    }

    fun completeMock(jobId:String,result:JsonNode,repositoryResult:RepositoryResult?=null,verificationResult:VerificationResult?=null,verificationFailureCode:String?=null) {
        val now=Instant.now()
        val failed=verificationFailureCode!=null
        val status=if(failed)JobStatus.FAILED else JobStatus.SUCCEEDED
        val phase=if(failed)"VERIFICATION_FAILED" else "COMPLETED"
        jdbc.update("UPDATE runtime_v2_job SET status=?,phase=?,progress_percent=100,result_json=?,repository_result_json=?,verification_result_json=?,error_code=?,error_message=?,completed_at=?,updated_at=? WHERE id=? AND status IN ('QUEUED','WAITING_FOR_WORKER')",status.name,phase,result.toString(),repositoryResult?.let(mapper::writeValueAsString),verificationResult?.let(mapper::writeValueAsString),verificationFailureCode,verificationFailureCode?.let { "Prepared mock verification failure." },utc(now),utc(now),jobId)
        addEvent(jobId,null,EventType.JOB_FINISHED,phase,if(failed)"Mock job failed verification." else "Mock job completed.",status=status,percent=100)
    }

    fun failAttempt(job:StoredV2Job,attemptId:String,code:String,message:String,retryable:Boolean,forceRetry:Boolean=false) {
        val now=Instant.now(); val mayRetry=retryable && (job.view.attemptCount < job.view.maxAttempts || forceRetry)
        if(code=="CANCELLED"||job.cancelRequested){
            jdbc.update("UPDATE runtime_v2_attempt SET status='CANCELLED',error_code='CANCELLED',completed_at=? WHERE id=? AND status='RUNNING'",utc(now),attemptId)
            jdbc.update("UPDATE runtime_v2_job SET status='CANCELLED',phase='CANCELLED',error_code=NULL,error_message=NULL,completed_at=?,updated_at=? WHERE id=?",utc(now),utc(now),job.view.id)
            addEvent(job.view.id,attemptId,EventType.JOB_FINISHED,"CANCELLED",message,status=JobStatus.CANCELLED)
            return
        }
        jdbc.update("UPDATE runtime_v2_attempt SET status='FAILED',error_code=?,completed_at=? WHERE id=? AND status='RUNNING'",code.take(120),utc(now),attemptId)
        addEvent(job.view.id,attemptId,EventType.ATTEMPT_FINISHED,"FAILED","$code: ${message.take(800)}")
        if(mayRetry) {
            val notBefore=now.plusSeconds((5L shl (job.view.attemptCount-1).coerceAtLeast(0)).coerceAtMost(300))
            jdbc.update("UPDATE runtime_v2_job SET status='QUEUED',phase='RETRY_WAIT',error_code=?,error_message=?,not_before=?,updated_at=? WHERE id=?",code.take(120),message.take(2000),utc(notBefore),utc(now),job.view.id)
            addEvent(job.view.id,attemptId,EventType.RETRY_SCHEDULED,"RETRY_WAIT","Retry scheduled after $code.",status=JobStatus.QUEUED)
        } else {
            jdbc.update("UPDATE runtime_v2_job SET status='FAILED',phase='FAILED',error_code=?,error_message=?,completed_at=?,updated_at=? WHERE id=?",code.take(120),message.take(2000),utc(now),utc(now),job.view.id)
            addEvent(job.view.id,attemptId,EventType.JOB_FINISHED,"FAILED","Job failed: $code.",status=JobStatus.FAILED)
        }
    }

    fun rejectOutput(job:StoredV2Job,attemptId:String,code:String,message:String):Boolean {
        failAttempt(job,attemptId,code,message,true)
        jdbc.update("UPDATE runtime_v2_attempt SET status='INVALID_OUTPUT' WHERE id=? AND status='FAILED'",attemptId)
        return job.view.attemptCount < job.view.maxAttempts
    }
    fun markAttemptAbandoned(attemptId:String){jdbc.update("UPDATE runtime_v2_attempt SET status='ABANDONED' WHERE id=? AND status='FAILED'",attemptId)}

    fun cancel(job:StoredV2Job) {
        val now=Instant.now()
        if(job.view.status in setOf(JobStatus.QUEUED,JobStatus.WAITING_FOR_WORKER)) {
            jdbc.update("UPDATE runtime_v2_job SET status='CANCELLED',phase='CANCELLED',cancel_requested=TRUE,completed_at=?,updated_at=? WHERE id=?",utc(now),utc(now),job.view.id)
            addEvent(job.view.id,null,EventType.JOB_FINISHED,"CANCELLED","Job cancelled.",status=JobStatus.CANCELLED)
        } else if(job.view.status==JobStatus.RUNNING) jdbc.update("UPDATE runtime_v2_job SET cancel_requested=TRUE,updated_at=? WHERE id=?",utc(now),job.view.id)
    }

    fun addEvent(jobId:String,attemptId:String?,type:EventType,phase:String?,message:String?,kind:LogKind?=null,text:String?=null,streamId:String?=null,final:Boolean?=null,status:JobStatus?=null,percent:Int?=null,externalId:String?=null) {
        try { jdbc.update("""INSERT INTO runtime_v2_event(job_id,attempt_id,event_type,phase,message,log_kind,log_text,log_stream_id,log_final,job_status,progress_percent,external_event_id,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",jobId,attemptId,type.name,phase,message?.take(1000),kind?.name,text?.take(8192),streamId?.take(200),final,status?.name,percent,externalId?.take(200),utc(Instant.now())) } catch(error:org.springframework.dao.DuplicateKeyException) { /* idempotent worker event */ }
    }

    fun events(jobId:String,after:Long,limit:Int):List<JobEventView> = jdbc.query("SELECT * FROM runtime_v2_event WHERE job_id=? AND sequence_number>? ORDER BY sequence_number LIMIT ?",{rs,_->
        JobEventView(rs.getLong("sequence_number"),jobId,EventType.valueOf(rs.getString("event_type")),rs.getString("phase"),rs.getString("message"),rs.getString("log_kind")?.let(LogKind::valueOf),rs.getString("log_text"),rs.getString("log_stream_id"),rs.getObject("log_final") as? Boolean,rs.getString("job_status")?.let(JobStatus::valueOf),rs.getObject("progress_percent") as? Int,instant(rs.getObject("created_at")))
    },jobId,after,limit.coerceIn(1,500))

    fun requestContentDelete(jobId:String):Instant { val now=Instant.now(); jdbc.update("UPDATE runtime_v2_job SET content_delete_requested_at=? WHERE id=?",utc(now),jobId); return now }

    private fun rowMapper() = org.springframework.jdbc.core.RowMapper<StoredV2Job> { rs,_ ->
        val requestTree=mapper.readTree(rs.getString("request_json")).also { if(it.isObject)(it as com.fasterxml.jackson.databind.node.ObjectNode).remove("repositoryRequest") }
        val request=mapper.treeToValue(requestTree,CreateJobRequest::class.java)
        StoredV2Job(
            JobView(rs.getString("id"),rs.getString("tenant_id"),rs.getString("idempotency_key"),JobKind.valueOf(rs.getString("job_kind")),TaskType.valueOf(rs.getString("task_type")),ExecutionSelection(rs.getString("vendor_id"),rs.getString("model"),ExecutionMode.valueOf(rs.getString("execution_mode"))),JobStatus.valueOf(rs.getString("status")),rs.getString("phase"),rs.getInt("attempt_count"),rs.getInt("max_attempts"),rs.getObject("progress_percent") as? Int,rs.getString("progress_message"),rs.getString("error_code"),rs.getString("error_message"),instant(rs.getObject("created_at")),instant(rs.getObject("updated_at")),rs.getObject("completed_at")?.let(::instant)),
            request,
            rs.getString("result_json")?.let(mapper::readTree),
            rs.getString("repository_result_json")?.let { mapper.readValue(it,RepositoryResult::class.java) },
            rs.getString("verification_result_json")?.let { mapper.readValue(it,VerificationResult::class.java) },
            rs.getBoolean("cancel_requested"),
        )
    }
    private fun attemptRowMapper()=org.springframework.jdbc.core.RowMapper<StoredV2Attempt>{rs,_->
        val quality=UsageQuality.valueOf(rs.getString("usage_quality")); val view=AttemptView(rs.getString("id"),rs.getString("job_id"),rs.getInt("attempt_number"),AttemptReason.valueOf(rs.getString("reason")),AttemptStatus.valueOf(rs.getString("status")),ExecutionSelection(rs.getString("vendor_id"),rs.getString("model"),ExecutionMode.valueOf(rs.getString("execution_mode"))),rs.getString("provider_request_id"),quality,JobUsageSummary(rs.getInt("attempt_number"),quality),rs.getString("error_code"),instant(rs.getObject("started_at")),rs.getObject("completed_at")?.let(::instant))
        StoredV2Attempt(view,rs.getString("worker_id"),rs.getString("worker_boot_id"),rs.getString("fencing_token_hash"),instant(rs.getObject("lease_until")),instant(rs.getObject("attempt_deadline")))
    }
    companion object { fun utc(value:Instant)=value.atOffset(ZoneOffset.UTC); fun instant(value:Any):Instant=when(value){is OffsetDateTime->value.toInstant();is java.sql.Timestamp->value.toInstant();else->error("Unsupported timestamp")}; fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)} }
}

internal fun v2ProviderName(vendorId:String)=when(vendorId){"openai"->"CODEX";"anthropic"->"CLAUDE";"mock"->"MOCKED";else->vendorId.uppercase()}

@Service
class V2JobService(private val properties:RuntimeProperties,private val jobs:V2JobStore,private val objects:V2ObjectStore,private val validator:JsonResultValidator,private val mapper:ObjectMapper) {
    @Transactional
    fun create(tenantId:String,request:CreateJobRequest):StoredV2Job {
        jobs.findByIdempotency(tenantId,request.idempotencyKey)?.let { existing ->
            if(existing.request!=request) throw ApiException("IDEMPOTENCY_CONFLICT","The idempotency key already represents a different request.",HttpStatus.CONFLICT)
            return existing
        }
        validateSelection(tenantId,request)
        validator.validateSchema(request.output.resultSchema)
        if(request.taskType in setOf(TaskType.STRUCTURED_GENERATION,TaskType.REPOSITORY_AGENT) && request.output.resultSchema==null) throw ApiException("RESULT_SCHEMA_REQUIRED","Structured and repository jobs require resultSchema.")
        validateRepositoryRequest(tenantId, request)
        val prefixes=properties.allowedEnvironmentPrefixes(tenantId)
        request.environmentKeys.forEach { key -> if(key.substringBefore("__") !in prefixes) throw ApiException("ENVIRONMENT_KEY_NOT_ALLOWED","Environment key $key is outside the tenant policy.") }
        if(request.output.artifacts.map{it.name}.distinct().size!=request.output.artifacts.size) throw ApiException("DUPLICATE_OUTPUT_NAME","Output artifact names must be unique.")
        val refs=request.input.objects
        if(refs.map{it.name}.distinct().size!=refs.size) throw ApiException("DUPLICATE_INPUT_NAME","Input object names must be unique.")
        val storedObjects=refs.map { ref -> objects.objectById(ref.objectId)?.takeIf{it.tenantId==tenantId&&it.view.state=="READY"&&it.deletedAt==null}
            ?: throw ApiException("INPUT_OBJECT_NOT_READY","Input object ${ref.objectId} is unavailable.",HttpStatus.UNPROCESSABLE_ENTITY) }
        if(refs.any{objects.isBound(it.objectId)}) throw ApiException("INPUT_OBJECT_ALREADY_BOUND","An input object can belong to only one job.",HttpStatus.CONFLICT)
        if(storedObjects.sumOf{it.view.sizeBytes}>properties.jobInputMaxBytes) throw ApiException("JOB_INPUT_TOO_LARGE","Input objects exceed the 5 GiB job limit.",HttpStatus.PAYLOAD_TOO_LARGE)
        if(request.execution.mode==ExecutionMode.API&&request.execution.vendorId=="openai"&&request.taskType==TaskType.STRUCTURED_GENERATION&&storedObjects.any{it.view.sizeBytes>512L*1024*1024}) throw ApiException("PROVIDER_INPUT_TOO_LARGE","OpenAI Files accepts at most 512 MiB per structured-generation input object.",HttpStatus.UNPROCESSABLE_ENTITY)
        val job=jobs.insert(tenantId,request,properties.maxAttempts)
        refs.forEachIndexed{i,ref->objects.link(job.view.id,ref.objectId,"INPUT",ref.role.name,ref.name,i)}
        return job
    }

    fun validateSelection(tenantId:String,request:CreateJobRequest) {
        val e=request.execution
        if(e.mode==ExecutionMode.MOCK && (e.vendorId!="mock"||e.model!="mock")) throw ApiException("INVALID_EXECUTION","MOCK requires vendorId and model 'mock'.")
        if(e.mode!=ExecutionMode.MOCK && e.vendorId=="mock") throw ApiException("INVALID_EXECUTION","The mock vendor is only valid in MOCK mode.")
        if(properties.environment==RuntimeEnvironment.PRODUCTION && e.mode==ExecutionMode.MOCK) throw ApiException("MOCK_NOT_ALLOWED","Mock execution is disabled in production.",HttpStatus.UNPROCESSABLE_ENTITY)
        val providerName=v2ProviderName(e.vendorId)
        if(providerName !in properties.allowedProviders(tenantId)) throw ApiException("EXECUTION_NOT_ALLOWED","Vendor ${e.vendorId} is not allowed for $tenantId.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(!properties.modelAllowed(tenantId,e.model)) throw ApiException("EXECUTION_NOT_ALLOWED","Model ${e.model} is not allowed for $tenantId.",HttpStatus.UNPROCESSABLE_ENTITY)
        val supported = when(e.mode) {
            ExecutionMode.MOCK -> e.vendorId=="mock"
            ExecutionMode.SUBSCRIPTION -> e.vendorId in setOf("openai","anthropic") && request.taskType in setOf(TaskType.STRUCTURED_GENERATION,TaskType.REPOSITORY_AGENT)
            ExecutionMode.API -> (e.vendorId=="openai" && request.taskType in setOf(TaskType.STRUCTURED_GENERATION,TaskType.TRANSCRIPTION,TaskType.SPEECH_SYNTHESIS)) || (e.vendorId=="elevenlabs" && request.taskType==TaskType.SPEECH_SYNTHESIS)
            ExecutionMode.LOCAL -> e.vendorId=="local" && request.taskType==TaskType.TRANSCRIPTION
        }
        if(!supported) throw ApiException("EXECUTION_NOT_SUPPORTED","This exact vendor/model/mode/task combination has no executor.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(e.mode==ExecutionMode.API && e.vendorId=="openai" && properties.openAiApiKey.isBlank()) throw ApiException("EXECUTION_NOT_CONFIGURED","OpenAI API execution is not configured on this Runtime.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(e.mode==ExecutionMode.API && e.vendorId=="elevenlabs" && properties.elevenLabsApiKey.isBlank()) throw ApiException("EXECUTION_NOT_CONFIGURED","ElevenLabs API execution is not configured on this Runtime.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(e.mode==ExecutionMode.API && request.taskType==TaskType.STRUCTURED_GENERATION && request.output.artifacts.any{it.required}) throw ApiException("EXECUTION_NOT_SUPPORTED","OpenAI structured API jobs cannot currently produce required file artifacts.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(request.taskType==TaskType.SPEECH_SYNTHESIS) validateSynthesis(request)
        if(request.taskType==TaskType.TRANSCRIPTION && e.mode in setOf(ExecutionMode.API,ExecutionMode.LOCAL) && request.input.objects.size!=1) throw ApiException("INVALID_TRANSCRIPTION_INPUT","Transcription requires exactly one input object.",HttpStatus.UNPROCESSABLE_ENTITY)
    }

    private fun validateSynthesis(request:CreateJobRequest) {
        val audio=request.output.artifacts
        if(audio.size!=1||"audio/mpeg" !in audio.single().mimeTypes) throw ApiException("INVALID_SYNTHESIS_REQUEST","Speech synthesis requires exactly one declared audio/mpeg output artifact.",HttpStatus.UNPROCESSABLE_ENTITY)
        val objects=request.input.objects
        if(objects.size>1||objects.any{it.name!="text"}) throw ApiException("INVALID_SYNTHESIS_REQUEST","Speech synthesis accepts at most one input object named 'text'.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(objects.isEmpty()&&request.synthesis?.voice.isNullOrBlank()) throw ApiException("INVALID_SYNTHESIS_REQUEST","synthesis.voice is required for instruction text.",HttpStatus.UNPROCESSABLE_ENTITY)
    }

    fun validateResult(job:StoredV2Job,result:JsonNode,outputIds:Set<String>):List<ValidationError> {
        val errors=mutableListOf<ValidationError>()
        validator.validate(job.request.output.resultSchema,result).forEach{errors+=ValidationError(it.path,it.keyword,it.message)}
        if(result.toString().toByteArray().size>properties.v2ResultMaxBytes) errors+=ValidationError("$","maxSize","result.json exceeds ${properties.v2ResultMaxBytes} bytes.")
        val outputs=objects.outputObjects(job.view.id).filter{it.second.view.objectId in outputIds}
        job.request.output.artifacts.filter{it.required}.filter{required->outputs.none{it.first==required.name}}.forEach{errors+=ValidationError("$.artifacts.${it.name}","required","Required artifact is missing.")}
        if(outputs.size!=outputIds.size) errors+=ValidationError("$.outputObjectIds","ownership","One or more output objects are not ready for this job.")
        return errors.take(25)
    }

    fun validateRepositoryResult(job: StoredV2Job, value: RepositoryResult?): List<ValidationError> {
        val checkout = job.request.repositoryCheckout
        if (checkout == null) return if (value == null) emptyList() else listOf(
            ValidationError("$.repositoryResult", "forbidden", "Repository metadata is forbidden without repositoryCheckout."),
        )
        if (value == null) return listOf(ValidationError("$.repositoryResult", "required", "Repository metadata is required."))
        val errors = mutableListOf<ValidationError>()
        if (value.alias != checkout.alias) errors += ValidationError("$.repositoryResult.alias", "const", "Alias differs from the request.")
        if (value.branch != checkout.branch) errors += ValidationError("$.repositoryResult.branch", "const", "Branch differs from the request.")
        when (checkout.publicationMode) {
            RepositoryPublicationMode.NONE -> {
                if (value.publicationStatus != RepositoryPublicationStatus.NONE) errors += ValidationError("$.repositoryResult.publicationStatus", "const", "Read-only checkout requires NONE.")
                if (value.commitSha != null) errors += ValidationError("$.repositoryResult.commitSha", "forbidden", "Read-only checkout has no published commit.")
            }
            RepositoryPublicationMode.COMMIT_AND_PUSH -> when (value.publicationStatus) {
                RepositoryPublicationStatus.NONE -> errors += ValidationError("$.repositoryResult.publicationStatus", "enum", "Mutating checkout requires NO_CHANGES or PUSHED.")
                RepositoryPublicationStatus.NO_CHANGES -> if (value.commitSha != null) errors += ValidationError("$.repositoryResult.commitSha", "forbidden", "NO_CHANGES has no new commit.")
                RepositoryPublicationStatus.PUSHED -> if (value.commitSha == null) errors += ValidationError("$.repositoryResult.commitSha", "required", "PUSHED requires commitSha.")
            }
        }
        return errors
    }

    fun validateVerificationResult(job: StoredV2Job, value: VerificationResult?, repositoryResult: RepositoryResult? = null): List<ValidationError> {
        val verification = job.request.verification
        if (verification == null || verification.mode == VerificationMode.NONE) {
            return if (value == null) emptyList() else listOf(
                ValidationError("$.verificationResult", "forbidden", "Verification metadata is forbidden when repository verification is disabled."),
            )
        }
        if (value == null) {
            return if (repositoryResult?.publicationStatus == RepositoryPublicationStatus.NO_CHANGES) emptyList() else listOf(
                ValidationError("$.verificationResult", "required", "Verification metadata is required for a non-empty repository change."),
            )
        }
        val errors = mutableListOf<ValidationError>()
        if (value.agentRounds > verification.maxRepairAttempts + 1) {
            errors += ValidationError("$.verificationResult.agentRounds", "maximum", "Agent rounds exceed the requested repair limit.")
        }
        if (value.commands.map { it.id }.distinct().size != value.commands.size) {
            errors += ValidationError("$.verificationResult.commands", "uniqueItems", "Verification command IDs must be unique.")
        }
        value.commands.forEachIndexed { index, command ->
            if (command.status == VerificationCommandStatus.PASSED && command.exitCode != 0) {
                errors += ValidationError("$.verificationResult.commands[$index].exitCode", "const", "A passed command requires exitCode 0.")
            }
            if (command.status in setOf(VerificationCommandStatus.SKIPPED, VerificationCommandStatus.TIMEOUT) && command.exitCode != null) {
                errors += ValidationError("$.verificationResult.commands[$index].exitCode", "forbidden", "A skipped or timed-out command has no exitCode.")
            }
            if (command.status == VerificationCommandStatus.FAILED && (command.exitCode == null || command.exitCode == 0)) {
                errors += ValidationError("$.verificationResult.commands[$index].exitCode", "invalid", "A failed command requires a non-zero exitCode.")
            }
        }
        when (value.status) {
            VerificationStatus.PASSED -> if (value.commands.none { it.status == VerificationCommandStatus.PASSED } || value.commands.any { it.status in setOf(VerificationCommandStatus.FAILED, VerificationCommandStatus.TIMEOUT) }) {
                errors += ValidationError("$.verificationResult.status", "consistency", "PASSED requires at least one passed command and no failed commands.")
            }
            VerificationStatus.SKIPPED -> if (value.commands.isEmpty() || value.commands.any { it.status != VerificationCommandStatus.SKIPPED }) {
                errors += ValidationError("$.verificationResult.status", "consistency", "SKIPPED requires one or more skipped commands.")
            }
            VerificationStatus.FAILED -> if (value.commands.none { it.status in setOf(VerificationCommandStatus.FAILED, VerificationCommandStatus.TIMEOUT) }) {
                errors += ValidationError("$.verificationResult.status", "consistency", "FAILED requires a failed or timed-out command.")
            }
            VerificationStatus.TIMEOUT -> Unit
            VerificationStatus.CONFIG_MISSING, VerificationStatus.CONFIG_INVALID -> if (value.commands.isNotEmpty()) {
                errors += ValidationError("$.verificationResult.commands", "empty", "Configuration failures cannot contain executed commands.")
            }
        }
        if (value.status in setOf(VerificationStatus.PASSED, VerificationStatus.FAILED, VerificationStatus.SKIPPED, VerificationStatus.TIMEOUT) && value.configVersion == null) {
            errors += ValidationError("$.verificationResult.configVersion", "required", "Executed verification requires a configuration version.")
        }
        if (value.status in setOf(VerificationStatus.CONFIG_MISSING, VerificationStatus.CONFIG_INVALID) && value.configVersion != null) {
            errors += ValidationError("$.verificationResult.configVersion", "forbidden", "An unavailable configuration has no reliable version.")
        }
        return errors
    }

    private fun validateRepositoryRequest(tenantId: String, request: CreateJobRequest) {
        val checkout = request.repositoryCheckout
        if (checkout != null && request.repositorySnapshot != null) throw ApiException("INVALID_REPOSITORY_CHECKOUT", "repositorySnapshot and repositoryCheckout are mutually exclusive.")
        if (checkout != null) {
            if (tenantId != "software-factory") throw ApiException("REPOSITORY_ALIAS_NOT_ALLOWED", "Only software-factory may request repositoryCheckout.", HttpStatus.FORBIDDEN)
            if (checkout.alias !in properties.allowedRepositoryAliases(tenantId)) throw ApiException("REPOSITORY_ALIAS_NOT_ALLOWED", "Repository alias is outside the tenant policy.", HttpStatus.FORBIDDEN)
            if (!validBranch(checkout.branch)) throw ApiException("INVALID_REPOSITORY_CHECKOUT", "Branch name is not a valid Git branch.")
        }
        val verification = request.verification
        if (verification != null && checkout == null) throw ApiException("INVALID_VERIFICATION", "verification requires repositoryCheckout.")
        if (verification?.mode == VerificationMode.REPOSITORY_CONFIG && checkout?.publicationMode != RepositoryPublicationMode.COMMIT_AND_PUSH) {
            throw ApiException("INVALID_VERIFICATION", "REPOSITORY_CONFIG verification requires COMMIT_AND_PUSH.")
        }
        if (verification?.mode == VerificationMode.REPOSITORY_CONFIG && request.executionTimeoutSeconds < 600) {
            throw ApiException("INVALID_VERIFICATION", "REPOSITORY_CONFIG verification requires executionTimeoutSeconds of at least 600.")
        }
        when (request.jobKind) {
            JobKind.REPOSITORY_WORK -> {
                if (request.taskType != TaskType.REPOSITORY_AGENT || checkout == null || checkout.publicationMode != RepositoryPublicationMode.COMMIT_AND_PUSH) {
                    throw ApiException("INVALID_REPOSITORY_CHECKOUT", "REPOSITORY_WORK requires REPOSITORY_AGENT and COMMIT_AND_PUSH checkout.")
                }
            }
            JobKind.APPLICATION_WORK -> {
                if (checkout != null && (request.taskType != TaskType.REPOSITORY_AGENT || checkout.publicationMode != RepositoryPublicationMode.NONE)) {
                    throw ApiException("INVALID_REPOSITORY_CHECKOUT", "APPLICATION_WORK checkout requires REPOSITORY_AGENT and publication mode NONE.")
                }
                if (request.taskType == TaskType.REPOSITORY_AGENT && checkout == null) {
                    throw ApiException("INVALID_REPOSITORY_CHECKOUT", "REPOSITORY_AGENT application work requires a read-only checkout.")
                }
            }
        }
    }

    private fun validBranch(branch: String): Boolean {
        if (branch.isBlank() || branch.length > 240 || branch.startsWith('-') || branch.startsWith('/') || branch.endsWith('/') || branch.endsWith('.') || branch.contains("..") || branch.contains("@{") || branch.contains("//")) return false
        if (branch.any { it.code < 32 || it.code == 127 || it in " ~^:?*[\\" }) return false
        return branch.split('/').all { part -> part.isNotBlank() && part !in setOf(".", "..") && !part.startsWith('.') && !part.endsWith(".lock") }
    }
}

@Service
class V2AttemptRecovery(private val properties:RuntimeProperties,private val jobs:V2JobStore,private val uploads:V2UploadService,private val publications:V2RepositoryPublicationStore) {
    @Scheduled(fixedDelay=30_000)
    fun recover() { jobs.expiredAttempts(Instant.now().minusSeconds(properties.recoverySeconds)).forEach{attempt->jobs.find(attempt.view.jobId)?.let{job->val hasPublication=publications.find(job.view.id)!=null;if(!hasPublication)uploads.discardAttemptOutputs(job.view.id,attempt.view.id);jobs.failAttempt(job,attempt.view.id,"WORKER_LEASE_EXPIRED","Worker stopped heartbeating before the execution completed.",true,hasPublication);jobs.markAttemptAbandoned(attempt.view.id)}} }
}
