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

data class RegisteredV2WorkerDetails(
    val view: WorkerView,
    val environmentKeys: Set<String>,
    val repositoryAliases: Set<String>,
    val versions: Map<String, String>,
)

@Repository
class V2WorkerStore(private val jdbc:JdbcTemplate,private val mapper:ObjectMapper) {
    fun register(request:WorkerRegistrationRequest):WorkerView {
        val now=Instant.now()
        val updated=jdbc.update("""UPDATE runtime_v2_worker SET boot_id=?,executors_json=?,environment_keys_json=?,repository_aliases_json=?,max_concurrency=?,versions_json=?,last_heartbeat_at=? WHERE worker_id=?""",
            request.bootId,mapper.writeValueAsString(request.executors),mapper.writeValueAsString(request.availableEnvironmentKeys),mapper.writeValueAsString(request.availableRepositoryAliases),request.maxConcurrency,mapper.writeValueAsString(request.versions),V2JobStore.utc(now),request.workerId)
        if(updated==0)jdbc.update("""INSERT INTO runtime_v2_worker(worker_id,boot_id,executors_json,environment_keys_json,repository_aliases_json,max_concurrency,versions_json,last_heartbeat_at,registered_at) VALUES (?,?,?,?,?,?,?,?,?)""",
            request.workerId,request.bootId,mapper.writeValueAsString(request.executors),mapper.writeValueAsString(request.availableEnvironmentKeys),mapper.writeValueAsString(request.availableRepositoryAliases),request.maxConcurrency,mapper.writeValueAsString(request.versions),V2JobStore.utc(now),V2JobStore.utc(now))
        return WorkerView(request.workerId,request.bootId,request.executors,request.availableRepositoryAliases,request.maxConcurrency,now)
    }
    fun bootId(workerId:String):String?=jdbc.query("SELECT boot_id FROM runtime_v2_worker WHERE worker_id=?",{rs,_->rs.getString(1)},workerId).firstOrNull()
    fun activeCount(workerId:String,bootId:String):Int=jdbc.queryForObject("SELECT COUNT(*) FROM runtime_v2_attempt WHERE worker_id=? AND worker_boot_id=? AND status='RUNNING'",Int::class.java,workerId,bootId)?:0
    fun maxConcurrency(workerId:String):Int=jdbc.queryForObject("SELECT max_concurrency FROM runtime_v2_worker WHERE worker_id=?",Int::class.java,workerId)?:0
    fun environmentKeys(workerId:String):Set<String> = jdbc.query("SELECT environment_keys_json FROM runtime_v2_worker WHERE worker_id=?",{rs,_->mapper.readValue(rs.getString(1),mapper.typeFactory.constructCollectionType(Set::class.java,String::class.java)) as Set<String>},workerId).firstOrNull().orEmpty()
    fun repositoryAliases(workerId:String):Set<String> = jdbc.query("SELECT repository_aliases_json FROM runtime_v2_worker WHERE worker_id=?",{rs,_->mapper.readValue(rs.getString(1),mapper.typeFactory.constructCollectionType(Set::class.java,String::class.java)) as Set<String>},workerId).firstOrNull().orEmpty()
    fun executors(workerId:String):Set<ExecutorCapability> = jdbc.query("SELECT executors_json FROM runtime_v2_worker WHERE worker_id=?",{rs,_->mapper.readValue(rs.getString(1),mapper.typeFactory.constructCollectionType(Set::class.java,ExecutorCapability::class.java)) as Set<ExecutorCapability>},workerId).firstOrNull().orEmpty()
    fun touch(workerId:String){jdbc.update("UPDATE runtime_v2_worker SET last_heartbeat_at=? WHERE worker_id=?",V2JobStore.utc(Instant.now()),workerId)}
    fun registeredWorkers():List<RegisteredV2WorkerDetails> = jdbc.query("SELECT * FROM runtime_v2_worker ORDER BY worker_id") { rs, _ ->
        val executors = mapper.readValue(
            rs.getString("executors_json"),
            mapper.typeFactory.constructCollectionType(Set::class.java, ExecutorCapability::class.java),
        ) as Set<ExecutorCapability>
        val environmentKeys = mapper.readValue(
            rs.getString("environment_keys_json"),
            mapper.typeFactory.constructCollectionType(Set::class.java, String::class.java),
        ) as Set<String>
        val versions = mapper.readValue(
            rs.getString("versions_json"),
            mapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java),
        ) as Map<String, String>
        val repositoryAliases = mapper.readValue(
            rs.getString("repository_aliases_json"),
            mapper.typeFactory.constructCollectionType(Set::class.java, String::class.java),
        ) as Set<String>
        RegisteredV2WorkerDetails(
            WorkerView(
                rs.getString("worker_id"), rs.getString("boot_id"), executors,
                repositoryAliases, rs.getInt("max_concurrency"), V2JobStore.instant(rs.getObject("last_heartbeat_at")),
            ),
            environmentKeys,
            repositoryAliases,
            versions,
        )
    }
}

@Service
class V2WorkerService(
    private val properties:RuntimeProperties,private val workers:V2WorkerStore,private val jobs:V2JobStore,
    private val objects:V2ObjectStore,private val uploads:V2UploadService,private val usage:V2UsageStore,private val jobService:V2JobService,
    private val publications:V2RepositoryPublicationStore,
) {
    fun register(request:WorkerRegistrationRequest)=workers.register(request)

    @Synchronized
    @Transactional
    fun claim(workerId:String,request:ClaimRequest):ClaimedJob? {
        if(workers.bootId(workerId)!=request.bootId)throw ApiException("WORKER_BOOT_MISMATCH","Worker must register this boot ID.",HttpStatus.CONFLICT)
        if(request.executors!=workers.executors(workerId))throw ApiException("WORKER_CAPABILITY_MISMATCH","Claim capabilities must equal the registered capabilities.",HttpStatus.CONFLICT)
        workers.touch(workerId)
        if(workers.activeCount(workerId,request.bootId)>=workers.maxConcurrency(workerId))return null
        val environmentKeys=workers.environmentKeys(workerId)
        val repositoryAliases=workers.repositoryAliases(workerId)
        val job=jobs.queued().firstOrNull{candidate->
            request.executors.any{cap->cap.vendorId==candidate.view.execution.vendorId&&cap.model==candidate.view.execution.model&&cap.mode==candidate.view.execution.mode&&candidate.view.taskType in cap.taskTypes} &&
                candidate.request.environmentKeys.all(environmentKeys::contains) &&
                candidate.request.repositoryCheckout?.alias?.let(repositoryAliases::contains) != false
        } ?: return null
        val token=UUID.randomUUID().toString()+UUID.randomUUID().toString();val now=Instant.now();val lease=now.plusSeconds(properties.leaseSeconds);val deadline=now.plusSeconds(job.request.executionTimeoutSeconds.toLong())
        val attempt=jobs.createAttempt(job,workerId,request.bootId,token,lease,deadline)
        workers.touch(workerId)
        return ClaimedJob(jobs.find(job.view.id)!!.view,attempt.view,token,lease,deadline,job.request,publications.find(job.view.id)?.view)
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
        val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken)
        val verificationFailure=request.verificationResult?.status in setOf(VerificationStatus.FAILED,VerificationStatus.CONFIG_MISSING,VerificationStatus.CONFIG_INVALID,VerificationStatus.TIMEOUT)
        val repositoryErrors=if(verificationFailure){if(request.repositoryResult==null)emptyList() else listOf(ValidationError("$.repositoryResult","forbidden","Failed verification cannot publish repository metadata."))}else jobService.validateRepositoryResult(job,request.repositoryResult)
        val errors=jobService.validateResult(job,request.result,request.outputObjectIds)+repositoryErrors+jobService.validateVerificationResult(job,request.verificationResult,request.repositoryResult)
        if(errors.isNotEmpty()){uploads.discardAttemptOutputs(jobId,attemptId);val scheduled=jobs.rejectOutput(job,attemptId,if(errors.any{it.code=="required"})"MISSING_REQUIRED_ARTIFACT" else "MODEL_OUTPUT_SCHEMA_INVALID",errors.joinToString("; "){it.message});return OutputRejectedResponse("OUTPUT_REJECTED",scheduled,errors)}
        if(request.repositoryResult?.publicationStatus==RepositoryPublicationStatus.PUSHED)throw ApiException("REPOSITORY_PUBLICATION_NOT_PREPARED","PUSHED results require the publication prepare/confirm protocol.",HttpStatus.CONFLICT)
        if(verificationFailure){
            val verification=requireNotNull(request.verificationResult)
            val code=when(verification.status){VerificationStatus.CONFIG_MISSING->"VERIFICATION_CONFIG_MISSING";VerificationStatus.CONFIG_INVALID->"VERIFICATION_CONFIG_INVALID";VerificationStatus.TIMEOUT->"VERIFICATION_TIMEOUT";else->"VERIFICATION_FAILED"}
            jobs.completeWithVerificationFailure(jobId,attemptId,request.result,verification,usage.attemptSummary(attemptId).usageQuality,code,"Repository verification did not pass; nothing was committed or pushed.")
        }else jobs.complete(jobId,attemptId,request.result,request.repositoryResult,request.verificationResult,usage.attemptSummary(attemptId).usageQuality)
        return null
    }

    @Transactional fun preparePublication(workerId:String,jobId:String,attemptId:String,request:PrepareRepositoryPublicationRequest):Pair<RepositoryPublicationIntentView,OutputRejectedResponse?> {
        val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken)
        val checkout=job.request.repositoryCheckout
        if(checkout==null||checkout.publicationMode!=RepositoryPublicationMode.COMMIT_AND_PUSH)throw ApiException("INVALID_REPOSITORY_CHECKOUT","Job is not a mutating repository checkout.",HttpStatus.CONFLICT)
        val errors=jobService.validateResult(job,request.result,request.outputObjectIds)+jobService.validateRepositoryResult(job,request.repositoryResult)+jobService.validateVerificationResult(job,request.verificationResult,request.repositoryResult)
        if(request.repositoryResult.publicationStatus!=RepositoryPublicationStatus.PUSHED)throw ApiException("INVALID_REPOSITORY_RESULT","Prepared publication requires intended status PUSHED.")
        if(request.verificationResult?.status !in setOf(null,VerificationStatus.PASSED,VerificationStatus.SKIPPED))throw ApiException("VERIFICATION_FAILED","Repository publication requires passed or skipped verification.",HttpStatus.CONFLICT)
        if(errors.isNotEmpty()){
            uploads.discardAttemptOutputs(jobId,attemptId)
            val scheduled=jobs.rejectOutput(job,attemptId,if(errors.any{it.code=="required"})"MISSING_REQUIRED_ARTIFACT" else "MODEL_OUTPUT_SCHEMA_INVALID",errors.joinToString("; "){it.message})
            return RepositoryPublicationIntentView(jobId,attemptId,checkout.alias,checkout.branch,request.repositoryResult.checkoutCommitSha,request.repositoryResult.commitSha!!,RepositoryPublicationIntentStatus.PREPARED,Instant.now()) to OutputRejectedResponse("OUTPUT_REJECTED",scheduled,errors)
        }
        val repositoryResult=request.repositoryResult
        val existing=publications.prepare(jobId,attemptId,checkout.alias,checkout.branch,repositoryResult.checkoutCommitSha,repositoryResult.commitSha!!,repositoryResult.diffStat,request.result,request.outputObjectIds,request.verificationResult)
        if(existing.result!=request.result||existing.outputObjectIds!=request.outputObjectIds||existing.diffStat!=repositoryResult.diffStat||existing.verificationResult!=request.verificationResult||existing.view.alias!=checkout.alias||existing.view.branch!=checkout.branch||existing.view.checkoutCommitSha!=repositoryResult.checkoutCommitSha||existing.view.intendedCommitSha!=repositoryResult.commitSha)throw ApiException("REPOSITORY_PUBLICATION_AMBIGUOUS","A different publication intent already exists.",HttpStatus.CONFLICT)
        jobs.progress(jobId,attemptId,"PUBLISHING",95,"Validated result stored; repository push may proceed.")
        return existing.view to null
    }

    @Transactional fun confirmPublication(workerId:String,jobId:String,attemptId:String,request:ConfirmRepositoryPublicationRequest) {
        val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken)
        val stored=publications.find(jobId)?:throw ApiException("REPOSITORY_PUBLICATION_AMBIGUOUS","Publication intent is unavailable.",HttpStatus.CONFLICT)
        if(stored.view.intendedCommitSha!=request.commitSha)throw ApiException("REPOSITORY_PUBLICATION_AMBIGUOUS","Confirmed commit differs from the prepared intent.",HttpStatus.CONFLICT)
        val repositoryResult=RepositoryResult(stored.view.alias,stored.view.branch,stored.view.checkoutCommitSha,RepositoryPublicationStatus.PUSHED,stored.view.intendedCommitSha,stored.diffStat)
        val errors=jobService.validateResult(job,stored.result,stored.outputObjectIds)+jobService.validateRepositoryResult(job,repositoryResult)+jobService.validateVerificationResult(job,stored.verificationResult,repositoryResult)
        if(errors.isNotEmpty())throw ApiException("REPOSITORY_PUBLICATION_AMBIGUOUS","Prepared output is no longer valid.",HttpStatus.CONFLICT)
        publications.markPushed(jobId,request.commitSha)
        jobs.complete(jobId,attemptId,stored.result,repositoryResult,stored.verificationResult,usage.attemptSummary(attemptId).usageQuality)
        publications.markFinalized(jobId)
    }

    @Transactional fun discardPublication(workerId:String,jobId:String,attemptId:String,request:DiscardRepositoryPublicationRequest) {
        authenticate(workerId,jobId,attemptId,request.fencingToken)
        val discarded=publications.discard(jobId,request.commitSha)?:throw ApiException("REPOSITORY_PUBLICATION_AMBIGUOUS","Publication intent cannot be discarded.",HttpStatus.CONFLICT)
        uploads.discardAttemptOutputs(jobId,discarded.view.attemptId)
    }
    fun fail(workerId:String,jobId:String,attemptId:String,request:FailAttemptRequest){val(job,_)=authenticate(workerId,jobId,attemptId,request.fencingToken);val hasPublication=publications.find(jobId)!=null;if(!hasPublication)uploads.discardAttemptOutputs(jobId,attemptId);jobs.failAttempt(job,attemptId,request.errorCode,request.message,request.retryable,hasPublication&&request.retryable)}
}
