package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Repository
class V2WorkerStore(private val jdbc:JdbcTemplate,private val mapper:ObjectMapper) {
    fun register(request:WorkerRegistrationRequest):WorkerView {
        val now=Instant.now()
        val updated=jdbc.update("""UPDATE runtime_v2_worker SET boot_id=?,executors_json=?,environment_keys_json=?,max_concurrency=?,versions_json=?,last_heartbeat_at=? WHERE worker_id=?""",
            request.bootId,mapper.writeValueAsString(request.executors),mapper.writeValueAsString(request.availableEnvironmentKeys),request.maxConcurrency,mapper.writeValueAsString(request.versions),V2JobStore.utc(now),request.workerId)
        if(updated==0)jdbc.update("""INSERT INTO runtime_v2_worker(worker_id,boot_id,executors_json,environment_keys_json,max_concurrency,versions_json,last_heartbeat_at,registered_at) VALUES (?,?,?,?,?,?,?,?)""",
            request.workerId,request.bootId,mapper.writeValueAsString(request.executors),mapper.writeValueAsString(request.availableEnvironmentKeys),request.maxConcurrency,mapper.writeValueAsString(request.versions),V2JobStore.utc(now),V2JobStore.utc(now))
        return WorkerView(request.workerId,request.bootId,request.executors,request.maxConcurrency,now)
    }
    fun bootId(workerId:String):String?=jdbc.query("SELECT boot_id FROM runtime_v2_worker WHERE worker_id=?",{rs,_->rs.getString(1)},workerId).firstOrNull()
    fun activeCount(workerId:String,bootId:String):Int=jdbc.queryForObject("SELECT COUNT(*) FROM runtime_v2_attempt WHERE worker_id=? AND worker_boot_id=? AND status='RUNNING'",Int::class.java,workerId,bootId)?:0
    fun maxConcurrency(workerId:String):Int=jdbc.queryForObject("SELECT max_concurrency FROM runtime_v2_worker WHERE worker_id=?",Int::class.java,workerId)?:0
    fun environmentKeys(workerId:String):Set<String> = jdbc.query("SELECT environment_keys_json FROM runtime_v2_worker WHERE worker_id=?",{rs,_->mapper.readValue(rs.getString(1),mapper.typeFactory.constructCollectionType(Set::class.java,String::class.java)) as Set<String>},workerId).firstOrNull().orEmpty()
    fun executors(workerId:String):Set<ExecutorCapability> = jdbc.query("SELECT executors_json FROM runtime_v2_worker WHERE worker_id=?",{rs,_->mapper.readValue(rs.getString(1),mapper.typeFactory.constructCollectionType(Set::class.java,ExecutorCapability::class.java)) as Set<ExecutorCapability>},workerId).firstOrNull().orEmpty()
    fun touch(workerId:String){jdbc.update("UPDATE runtime_v2_worker SET last_heartbeat_at=? WHERE worker_id=?",V2JobStore.utc(Instant.now()),workerId)}
}

@Service
class V2WorkerService(
    private val properties:RuntimeProperties,private val workers:V2WorkerStore,private val jobs:V2JobStore,
    private val objects:V2ObjectStore,private val uploads:V2UploadService,private val usage:V2UsageStore,private val jobService:V2JobService,
) {
    fun register(request:WorkerRegistrationRequest)=workers.register(request)

    @Synchronized
    @Transactional
    fun claim(workerId:String,request:ClaimRequest):ClaimedJob? {
        if(workers.bootId(workerId)!=request.bootId)throw ApiException("WORKER_BOOT_MISMATCH","Worker must register this boot ID.",HttpStatus.CONFLICT)
        if(request.executors!=workers.executors(workerId))throw ApiException("WORKER_CAPABILITY_MISMATCH","Claim capabilities must equal the registered capabilities.",HttpStatus.CONFLICT)
        if(workers.activeCount(workerId,request.bootId)>=workers.maxConcurrency(workerId))return null
        val environmentKeys=workers.environmentKeys(workerId)
        val job=jobs.queued().firstOrNull{candidate->
            request.executors.any{cap->cap.vendorId==candidate.view.execution.vendorId&&cap.model==candidate.view.execution.model&&cap.mode==candidate.view.execution.mode&&candidate.view.taskType in cap.taskTypes} &&
                candidate.request.environmentKeys.all(environmentKeys::contains)
        } ?: return null
        val token=UUID.randomUUID().toString()+UUID.randomUUID().toString();val now=Instant.now();val lease=now.plusSeconds(properties.leaseSeconds);val deadline=now.plusSeconds(job.request.executionTimeoutSeconds.toLong())
        val attempt=jobs.createAttempt(job,workerId,request.bootId,token,lease,deadline)
        workers.touch(workerId)
        return ClaimedJob(jobs.find(job.view.id)!!.view,attempt.view,token,lease,deadline,job.request)
    }

    fun authenticate(workerId:String,jobId:String,attemptId:String,token:String):Pair<StoredV2Job,StoredV2Attempt> {
        val attempt=jobs.attempt(attemptId)?:throw ApiException("ATTEMPT_FENCED","Attempt is unavailable.",HttpStatus.CONFLICT)
        val job=jobs.find(jobId)?:throw ApiException("NOT_FOUND","Job not found.",HttpStatus.NOT_FOUND)
        val valid=attempt.workerId==workerId&&attempt.view.jobId==jobId&&attempt.view.status==AttemptStatus.RUNNING&&MessageDigest.isEqual(attempt.fencingTokenHash.toByteArray(),V2JobStore.hash(token).toByteArray())&&Instant.now().isBefore(attempt.attemptDeadline)
        if(!valid)throw ApiException("ATTEMPT_FENCED","Attempt lease or fencing token is invalid.",HttpStatus.CONFLICT)
        return job to attempt
    }

    fun heartbeat(workerId:String,jobId:String,auth:AttemptAuth):HeartbeatResponse {
        val (job,attempt)=authenticate(workerId,jobId,auth.attemptId,auth.fencingToken)
        if(Instant.now().isAfter(attempt.leaseUntil.plusSeconds(properties.recoverySeconds)))return HeartbeatResponse(false,job.cancelRequested,true,null)
        val lease=Instant.now().plusSeconds(properties.leaseSeconds);jobs.extendLease(auth.attemptId,lease);workers.touch(workerId)
        return HeartbeatResponse(true,job.cancelRequested,false,lease)
    }
    fun progress(workerId:String,jobId:String,request:ProgressRequest){authenticate(workerId,jobId,request.attemptId,request.fencingToken);if(request.percent!=null&&request.percent !in 0..100)throw ApiException("INVALID_PROGRESS","Progress must be between 0 and 100.");jobs.progress(jobId,request.attemptId,request.phase,request.percent,request.message)}
    fun inputObject(workerId:String,jobId:String,objectId:String,attemptId:String,token:String):StoredV2Object { val (job,_)=authenticate(workerId,jobId,attemptId,token);return objects.linkedToJob(jobId,objectId)?.takeIf{it.tenantId==job.view.tenantId&&it.purpose=="INPUT"}?:throw ApiException("NOT_FOUND","Input object not found.",HttpStatus.NOT_FOUND) }

    fun reserveOutput(workerId:String,jobId:String,request:CreateOutputUploadRequest):UploadView {
        val (job,_)=authenticate(workerId,jobId,request.attemptId,request.fencingToken)
        val declaration=job.request.output.artifacts.firstOrNull{it.name==request.name}?:throw ApiException("UNDECLARED_OUTPUT","Output artifact '${request.name}' was not declared.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(request.mimeType !in declaration.mimeTypes)throw ApiException("OUTPUT_MIME_NOT_ALLOWED","Output MIME type is not declared.",HttpStatus.UNPROCESSABLE_ENTITY)
        if(declaration.maxBytes?.let{request.sizeBytes>it}==true)throw ApiException("OUTPUT_TOO_LARGE","Output exceeds its declared maximum.",HttpStatus.PAYLOAD_TOO_LARGE)
        if(objects.outputObjects(jobId).any{it.first==request.name})throw ApiException("OUTPUT_ALREADY_EXISTS","This output name is already finalized.",HttpStatus.CONFLICT)
        return uploads.reserveOutput(job.view.tenantId,jobId,request.attemptId,request.name,request.filename,request.mimeType,request.sizeBytes,request.sha256)
    }
    fun outputUpload(workerId:String,jobId:String,uploadId:String,attemptId:String,token:String):StoredV2Upload { authenticate(workerId,jobId,attemptId,token);return objects.upload(uploadId)?.takeIf{it.jobId==jobId&&it.attemptId==attemptId&&it.direction=="OUTPUT"}?:throw ApiException("NOT_FOUND","Output upload not found.",HttpStatus.NOT_FOUND) }
    fun appendOutput(workerId:String,jobId:String,uploadId:String,attemptId:String,token:String,offset:Long,input:java.io.InputStream):Long { val upload=outputUpload(workerId,jobId,uploadId,attemptId,token);return uploads.append(upload.tenantId,uploadId,offset,input,"OUTPUT") }
    @Transactional fun completeOutput(workerId:String,jobId:String,uploadId:String,auth:AttemptAuth):ObjectView { val (job,_)=authenticate(workerId,jobId,auth.attemptId,auth.fencingToken);val upload=outputUpload(workerId,jobId,uploadId,auth.attemptId,auth.fencingToken);val objectView=uploads.complete(job.view.tenantId,uploadId,"OUTPUT");if(!objects.isBound(objectView.objectId)){objects.link(jobId,objectView.objectId,"OUTPUT",null,upload.logicalName!!,objects.outputObjects(jobId).size);jobs.addEvent(jobId,auth.attemptId,EventType.OUTPUT_OBJECT_READY,"EXECUTING","Output ${upload.logicalName} is ready.")};return objectView }
    fun appendUsage(workerId:String,jobId:String,attemptId:String,request:AppendUsageRequest){val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken);usage.append(job,attemptId,request)}
    fun appendLog(workerId:String,jobId:String,attemptId:String,request:AppendLogRequest){authenticate(workerId,jobId,attemptId,request.fencingToken);jobs.addEvent(jobId,attemptId,EventType.LOG_MESSAGE,"EXECUTING",null,request.kind,request.text,request.streamId,request.final,externalId=request.eventId)}
    @Transactional fun submit(workerId:String,jobId:String,attemptId:String,request:SubmitResultRequest):OutputRejectedResponse? {
        val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken);val errors=jobService.validateResult(job,request.result,request.outputObjectIds)
        if(errors.isNotEmpty()){uploads.discardAttemptOutputs(jobId,attemptId);val scheduled=jobs.rejectOutput(job,attemptId,if(errors.any{it.code=="required"})"MISSING_REQUIRED_ARTIFACT" else "MODEL_OUTPUT_SCHEMA_INVALID",errors.joinToString("; "){it.message});return OutputRejectedResponse("OUTPUT_REJECTED",scheduled,errors)}
        jobs.complete(jobId,attemptId,request.result,usage.attemptSummary(attemptId).usageQuality);return null
    }
    fun fail(workerId:String,jobId:String,attemptId:String,request:FailAttemptRequest){val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken);uploads.discardAttemptOutputs(jobId,attemptId);jobs.failAttempt(job,attemptId,request.errorCode,request.message,request.retryable)}
}
