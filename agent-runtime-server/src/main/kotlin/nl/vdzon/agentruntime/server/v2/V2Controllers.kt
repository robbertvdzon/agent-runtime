package nl.vdzon.agentruntime.server.v2

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

fun HttpServletRequest.consumerTenant():String {
    val identity=ApiSecurity.identity(this)
    if(identity.role!=PrincipalRole.CONSUMER)throw ApiException("FORBIDDEN","Consumer credentials are required.",HttpStatus.FORBIDDEN)
    return identity.tenantId!!
}
fun HttpServletRequest.requireWorker(){if(ApiSecurity.identity(this).role!=PrincipalRole.WORKER)throw ApiException("FORBIDDEN","Worker credentials are required.",HttpStatus.FORBIDDEN)}
fun HttpServletRequest.requireAdmin(){if(ApiSecurity.identity(this).role!=PrincipalRole.ADMIN)throw ApiException("FORBIDDEN","Administrator credentials are required.",HttpStatus.FORBIDDEN)}

@Component
class V2Download(private val blobs:FilesystemBlobStore) {
    fun response(record:StoredV2Object,rangeHeader:String?):ResponseEntity<StreamingResponseBody> {
        val total=record.view.sizeBytes
        val range=parseRange(rangeHeader,total)
        val start=range?.first?:0L;val end=range?.last?:total-1;val length=end-start+1
        val body=StreamingResponseBody{output->blobs.openRange(record.blobKey,start).use{input->copy(input,output,length)}}
        val headers=HttpHeaders();headers.contentType=runCatching{MediaType.parseMediaType(record.view.mimeType)}.getOrDefault(MediaType.APPLICATION_OCTET_STREAM);headers.contentLength=length
        headers.set(HttpHeaders.ACCEPT_RANGES,"bytes");headers.eTag="\"${record.view.sha256}\"";headers.set(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"${record.view.filename.replace("\"","")}\"")
        if(range!=null)headers.set(HttpHeaders.CONTENT_RANGE,"bytes $start-$end/$total")
        return ResponseEntity(body,headers,if(range==null)HttpStatus.OK else HttpStatus.PARTIAL_CONTENT)
    }
    private fun parseRange(value:String?,size:Long):LongRange? {
        if(value==null)return null
        val match=Regex("bytes=(\\d*)-(\\d*)").matchEntire(value)?:throw ApiException("INVALID_RANGE","Invalid Range header.",HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        val left=match.groupValues[1];val right=match.groupValues[2]
        val range=when {left.isNotEmpty()->{val start=left.toLongOrNull()?:size;val end=right.toLongOrNull()?.coerceAtMost(size-1)?:size-1;start..end};right.isNotEmpty()->{val count=right.toLongOrNull()?.coerceAtMost(size)?:0;(size-count)..(size-1)};else->LongRange.EMPTY}
        if(range.isEmpty()||range.first<0||range.first>=size||range.last<range.first)throw ApiException("INVALID_RANGE","Requested range is not satisfiable.",HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        return range
    }
    private fun copy(input:InputStream,output:java.io.OutputStream,bytes:Long){val buffer=ByteArray(128*1024);var remaining=bytes;while(remaining>0){val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(count<0)break;output.write(buffer,0,count);remaining-=count}}
}

@RestController
@RequestMapping("/v2/uploads")
class V2UploadController(private val uploads:V2UploadService) {
    @PostMapping fun create(@Valid @RequestBody body:CreateUploadRequest,request:HttpServletRequest)=ResponseEntity.status(HttpStatus.CREATED).body(uploads.reserveInput(request.consumerTenant(),body.filename,body.mimeType,body.sizeBytes,body.sha256))
    @RequestMapping("/{uploadId}",method=[RequestMethod.HEAD]) fun head(@PathVariable uploadId:String,request:HttpServletRequest):ResponseEntity<Void>{val upload=uploads.get(request.consumerTenant(),uploadId,"INPUT");return ResponseEntity.noContent().header("Upload-Offset",upload.view.offset.toString()).header("Upload-Length",upload.view.sizeBytes.toString()).header("Upload-State",upload.view.state).build()}
    @PatchMapping("/{uploadId}",consumes=["application/offset+octet-stream"]) fun append(@PathVariable uploadId:String,@RequestHeader("Upload-Offset")offset:Long,request:HttpServletRequest):ResponseEntity<Void>{val next=uploads.append(request.consumerTenant(),uploadId,offset,request.inputStream,"INPUT");return ResponseEntity.noContent().header("Upload-Offset",next.toString()).build()}
    @DeleteMapping("/{uploadId}") fun delete(@PathVariable uploadId:String,request:HttpServletRequest):ResponseEntity<Void>{uploads.deleteInput(request.consumerTenant(),uploadId);return ResponseEntity.noContent().build()}
    @PostMapping("/{uploadId}/complete") fun complete(@PathVariable uploadId:String,request:HttpServletRequest)=uploads.complete(request.consumerTenant(),uploadId,"INPUT")
}

@RestController
@RequestMapping("/v2/jobs")
class V2JobController(private val service:V2JobService,private val jobs:V2JobStore,private val objects:V2ObjectStore,private val usage:V2UsageStore,private val download:V2Download) {
    @PostMapping fun create(@Valid @RequestBody body:CreateJobRequest,request:HttpServletRequest)=ResponseEntity.status(HttpStatus.ACCEPTED).body(service.create(request.consumerTenant(),body).view)
    @GetMapping fun list(@RequestParam(required=false)status:JobStatus?,@RequestParam(required=false)cursor:String?,@RequestParam(defaultValue="30")limit:Int,request:HttpServletRequest):JobPage {
        val items=jobs.list(request.consumerTenant(),status,cursor,limit+1).map{it.view};return JobPage(items.take(limit),if(items.size>limit)items[limit-1].id else null)
    }
    @GetMapping("/{jobId}") fun get(@PathVariable jobId:String,request:HttpServletRequest)=visible(request,jobId).view
    @GetMapping("/{jobId}/result") fun result(@PathVariable jobId:String,request:HttpServletRequest):JobResultView {val job=visible(request,jobId);if(job.view.status!=JobStatus.SUCCEEDED||job.result==null)throw ApiException("RESULT_NOT_READY","Job has no successful result.",HttpStatus.CONFLICT);val artifacts=objects.outputObjects(jobId).map{(name,o)->OutputObjectView(o.view.objectId,name,o.view.filename,o.view.mimeType,o.view.sizeBytes,o.view.sha256,o.view.state,o.view.createdAt,o.view.readyAt!!,"/v2/jobs/$jobId/objects/${o.view.objectId}/content")};return JobResultView(jobId,job.result,artifacts,usage.jobSummary(jobId),job.view.completedAt!!)}
    @GetMapping("/{jobId}/attempts") fun attempts(@PathVariable jobId:String,request:HttpServletRequest):List<AttemptView>{visible(request,jobId);return jobs.attempts(jobId).map{it.view.copy(usageSummary=usage.attemptSummary(it.view.id),usageQuality=usage.attemptSummary(it.view.id).usageQuality)}}
    @GetMapping("/{jobId}/events") fun events(@PathVariable jobId:String,@RequestParam(defaultValue="0")afterSequence:Long,@RequestParam(defaultValue="100")limit:Int,request:HttpServletRequest):JobEventPage{val job=visible(request,jobId);val items=jobs.events(jobId,afterSequence,limit);return JobEventPage(items,items.lastOrNull()?.sequence,job.view.status in setOf(JobStatus.QUEUED,JobStatus.WAITING_FOR_WORKER,JobStatus.RUNNING))}
    @GetMapping("/{jobId}/objects/{objectId}/content") fun content(@PathVariable jobId:String,@PathVariable objectId:String,@RequestHeader(HttpHeaders.RANGE,required=false)range:String?,request:HttpServletRequest):ResponseEntity<StreamingResponseBody>{val job=visible(request,jobId);val objectRecord=objects.linkedToJob(jobId,objectId)?.takeIf{it.tenantId==job.view.tenantId}?:throw ApiException("NOT_FOUND","Object not found.",HttpStatus.NOT_FOUND);return download.response(objectRecord,range)}
    @PostMapping("/{jobId}/cancel") fun cancel(@PathVariable jobId:String,request:HttpServletRequest):JobView{val job=visible(request,jobId);jobs.cancel(job);return jobs.find(jobId)!!.view}
    @DeleteMapping("/{jobId}/content") fun deleteContent(@PathVariable jobId:String,request:HttpServletRequest):ResponseEntity<ContentDeletionView>{val job=visible(request,jobId);if(job.view.status !in setOf(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED))throw ApiException("JOB_NOT_TERMINAL","Only terminal job content can be deleted.",HttpStatus.CONFLICT);val now=jobs.requestContentDelete(jobId);return ResponseEntity.accepted().body(ContentDeletionView(jobId,"SCHEDULED",now,null))}
    private fun visible(request:HttpServletRequest,id:String):StoredV2Job {val tenant=request.consumerTenant();return jobs.find(tenant,id)?:throw ApiException("NOT_FOUND","Job not found.",HttpStatus.NOT_FOUND)}
}

@RestController
class V2EventStreamController(private val jobs:V2JobStore) {
    private val executor=Executors.newVirtualThreadPerTaskExecutor()
    @GetMapping("/v2/jobs/{jobId}/event-stream",produces=[MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable jobId:String,@RequestHeader("Last-Event-ID",required=false)lastEventId:Long?,request:HttpServletRequest):SseEmitter {
        val tenant=request.consumerTenant();jobs.find(tenant,jobId)?:throw ApiException("NOT_FOUND","Job not found.",HttpStatus.NOT_FOUND)
        val emitter=SseEmitter(30*60*1000L);executor.submit{var cursor=lastEventId?:0L;try{while(true){val events=jobs.events(jobId,cursor,100);events.forEach{event->emitter.send(SseEmitter.event().id(event.sequence.toString()).name(event.type.name).data(event));cursor=event.sequence};val job=jobs.find(tenant,jobId)?:break;if(job.view.status !in setOf(JobStatus.QUEUED,JobStatus.WAITING_FOR_WORKER,JobStatus.RUNNING)&&events.isEmpty())break;if(events.isEmpty()){emitter.send(SseEmitter.event().comment("keep-alive"));Thread.sleep(1000)}};emitter.complete()}catch(error:Exception){emitter.completeWithError(error)}}
        return emitter
    }
}

@RestController
class V2UsageController(private val service:V2UsageService,private val store:V2UsageStore) {
    @GetMapping("/v2/usage/summary") fun tenant(@RequestParam(required=false)from:Instant?,@RequestParam(required=false)until:Instant?,@RequestParam(defaultValue="VENDOR,MODEL,MODE,TASK_TYPE")groupBy:String,@RequestParam(required=false)taskType:TaskType?,@RequestParam(required=false)vendorId:String?,@RequestParam(required=false)model:String?,@RequestParam(required=false)mode:ExecutionMode?,request:HttpServletRequest)=summary(from,until,groupBy,mapOf("taskType" to taskType?.name,"vendorId" to vendorId,"model" to model,"mode" to mode?.name),request.consumerTenant())
    @GetMapping("/v2/management/usage/summary") fun management(@RequestParam(required=false)from:Instant?,@RequestParam(required=false)until:Instant?,@RequestParam(defaultValue="TENANT,VENDOR,MODEL,MODE,TASK_TYPE")groupBy:String,@RequestParam(required=false)tenantId:String?,@RequestParam(required=false)taskType:TaskType?,@RequestParam(required=false)vendorId:String?,@RequestParam(required=false)model:String?,@RequestParam(required=false)mode:ExecutionMode?,request:HttpServletRequest):UsageSummaryResponse{request.requireAdmin();return summary(from,until,groupBy,mapOf("tenantId" to tenantId,"taskType" to taskType?.name,"vendorId" to vendorId,"model" to model,"mode" to mode?.name),null)}
    @GetMapping("/v2/management/prices") fun prices(@RequestParam(required=false)vendorId:String?,@RequestParam(required=false)model:String?,request:HttpServletRequest):List<PriceRateView>{request.requireAdmin();return store.prices(vendorId,model)}
    @PostMapping("/v2/management/prices") fun price(@Valid @RequestBody body:CreatePriceRateRequest,request:HttpServletRequest):ResponseEntity<PriceRateView>{request.requireAdmin();return ResponseEntity.status(HttpStatus.CREATED).body(store.createPrice(body))}
    @GetMapping("/v2/management/subscriptions") fun subscriptions(request:HttpServletRequest):List<SubscriptionPeriodView>{request.requireAdmin();return store.subscriptions()}
    @PostMapping("/v2/management/subscriptions") fun subscription(@Valid @RequestBody body:CreateSubscriptionPeriodRequest,request:HttpServletRequest):ResponseEntity<SubscriptionPeriodView>{request.requireAdmin();return ResponseEntity.status(HttpStatus.CREATED).body(store.createSubscription(body))}
    private fun summary(from:Instant?,until:Instant?,groupBy:String,filters:Map<String,String?>,tenant:String?):UsageSummaryResponse {val end=until?:Instant.now();val start=from?:end.minus(30,ChronoUnit.DAYS);return service.summary(start,end,tenant,groupBy.split(',').map(String::trim).filter(String::isNotBlank).toSet(),filters)}
}

@RestController
@RequestMapping("/v2/workers")
class V2WorkerController(private val service:V2WorkerService,private val download:V2Download) {
    @PostMapping("/register") fun register(@Valid @RequestBody body:WorkerRegistrationRequest,request:HttpServletRequest):WorkerView{request.requireWorker();return service.register(body)}
    @PostMapping("/{workerId}/claims") fun claim(@PathVariable workerId:String,@Valid @RequestBody body:ClaimRequest,request:HttpServletRequest):ResponseEntity<ClaimedJob>{request.requireWorker();val deadline=System.nanoTime()+body.waitSeconds*1_000_000_000L;do{val claim=service.claim(workerId,body);if(claim!=null)return ResponseEntity.ok(claim);if(body.waitSeconds==0)break;Thread.sleep(250)}while(System.nanoTime()<deadline);return ResponseEntity.noContent().build()}
    @PostMapping("/{workerId}/jobs/{jobId}/heartbeat") fun heartbeat(@PathVariable workerId:String,@PathVariable jobId:String,@Valid @RequestBody body:AttemptAuth,request:HttpServletRequest):HeartbeatResponse{request.requireWorker();return service.heartbeat(workerId,jobId,body)}
    @PostMapping("/{workerId}/jobs/{jobId}/progress") fun progress(@PathVariable workerId:String,@PathVariable jobId:String,@Valid @RequestBody body:ProgressRequest,request:HttpServletRequest):ResponseEntity<Void>{request.requireWorker();service.progress(workerId,jobId,body);return ResponseEntity.noContent().build()}
    @GetMapping("/{workerId}/jobs/{jobId}/objects/{objectId}/content") fun input(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable objectId:String,@RequestHeader("X-Attempt-Id")attemptId:String,@RequestHeader("X-Fencing-Token")token:String,request:HttpServletRequest):ResponseEntity<StreamingResponseBody>{request.requireWorker();return download.response(service.inputObject(workerId,jobId,objectId,attemptId,token),null)}
    @PostMapping("/{workerId}/jobs/{jobId}/output-objects") fun reserve(@PathVariable workerId:String,@PathVariable jobId:String,@Valid @RequestBody body:CreateOutputUploadRequest,request:HttpServletRequest):ResponseEntity<UploadView>{request.requireWorker();val upload=service.reserveOutput(workerId,jobId,body).copy(uploadUrl="/v2/workers/$workerId/jobs/$jobId/output-objects/");return ResponseEntity.status(HttpStatus.CREATED).body(upload.copy(uploadUrl=upload.uploadUrl+upload.uploadId))}
    @RequestMapping("/{workerId}/jobs/{jobId}/output-objects/{uploadId}",method=[RequestMethod.HEAD]) fun outputHead(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable uploadId:String,@RequestHeader("X-Attempt-Id")attemptId:String,@RequestHeader("X-Fencing-Token")token:String,request:HttpServletRequest):ResponseEntity<Void>{request.requireWorker();val upload=service.outputUpload(workerId,jobId,uploadId,attemptId,token);return ResponseEntity.noContent().header("Upload-Offset",upload.view.offset.toString()).header("Upload-Length",upload.view.sizeBytes.toString()).build()}
    @PatchMapping("/{workerId}/jobs/{jobId}/output-objects/{uploadId}",consumes=["application/offset+octet-stream"]) fun outputAppend(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable uploadId:String,@RequestHeader("X-Attempt-Id")attemptId:String,@RequestHeader("X-Fencing-Token")token:String,@RequestHeader("Upload-Offset")offset:Long,request:HttpServletRequest):ResponseEntity<Void>{request.requireWorker();val next=service.appendOutput(workerId,jobId,uploadId,attemptId,token,offset,request.inputStream);return ResponseEntity.noContent().header("Upload-Offset",next.toString()).build()}
    @PostMapping("/{workerId}/jobs/{jobId}/output-objects/{uploadId}/complete") fun outputComplete(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable uploadId:String,@Valid @RequestBody body:AttemptAuth,request:HttpServletRequest):ObjectView{request.requireWorker();return service.completeOutput(workerId,jobId,uploadId,body)}
    @PostMapping("/{workerId}/jobs/{jobId}/attempts/{attemptId}/usage-events") fun usage(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable attemptId:String,@Valid @RequestBody body:AppendUsageRequest,request:HttpServletRequest):ResponseEntity<Void>{request.requireWorker();service.appendUsage(workerId,jobId,attemptId,body);return ResponseEntity.noContent().build()}
    @PostMapping("/{workerId}/jobs/{jobId}/attempts/{attemptId}/logs") fun log(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable attemptId:String,@Valid @RequestBody body:AppendLogRequest,request:HttpServletRequest):ResponseEntity<Void>{request.requireWorker();service.appendLog(workerId,jobId,attemptId,body);return ResponseEntity.noContent().build()}
    @PostMapping("/{workerId}/jobs/{jobId}/attempts/{attemptId}/result") fun result(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable attemptId:String,@Valid @RequestBody body:SubmitResultRequest,request:HttpServletRequest):ResponseEntity<OutputRejectedResponse>{request.requireWorker();val rejection=service.submit(workerId,jobId,attemptId,body);return if(rejection==null)ResponseEntity.noContent().build() else ResponseEntity.unprocessableEntity().body(rejection)}
    @PostMapping("/{workerId}/jobs/{jobId}/attempts/{attemptId}/fail") fun fail(@PathVariable workerId:String,@PathVariable jobId:String,@PathVariable attemptId:String,@Valid @RequestBody body:FailAttemptRequest,request:HttpServletRequest):ResponseEntity<Void>{request.requireWorker();service.fail(workerId,jobId,attemptId,body);return ResponseEntity.noContent().build()}
}
