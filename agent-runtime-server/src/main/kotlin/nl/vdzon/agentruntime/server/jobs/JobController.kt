package nl.vdzon.agentruntime.server.jobs

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/jobs")
class JobController(private val service: JobService, private val store: JobStore) {
    @PostMapping
    fun create(@Valid @RequestBody body: CreateJobRequest, request: HttpServletRequest): ResponseEntity<JobView> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(service.create(ApiSecurity.identity(request), body))

    @GetMapping
    fun list(@RequestParam(required = false) status: JobStatus?, request: HttpServletRequest): List<JobView> {
        val identity = ApiSecurity.identity(request)
        return store.list(if (identity.role == PrincipalRole.ADMIN) null else identity.tenantId, status).map { it.view }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String, request: HttpServletRequest) = service.visibleJob(ApiSecurity.identity(request), id).view

    @GetMapping("/{id}/events")
    fun events(@PathVariable id: String, request: HttpServletRequest): List<JobEventView> {
        service.visibleJob(ApiSecurity.identity(request), id)
        return store.events(id)
    }

    @GetMapping("/{id}/result")
    fun result(@PathVariable id: String, request: HttpServletRequest) = service.result(ApiSecurity.identity(request), id)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: String, request: HttpServletRequest): JobView {
        val identity = ApiSecurity.identity(request)
        service.visibleJob(identity, id)
        store.requestCancel(id, identity.tenantId ?: "administrator")
        return store.find(id)!!.view
    }

    @GetMapping("/{jobId}/artifacts/{artifactId}")
    fun artifact(@PathVariable jobId: String, @PathVariable artifactId: String, request: HttpServletRequest): ResponseEntity<ByteArrayResource> {
        service.visibleJob(ApiSecurity.identity(request), jobId)
        val meta = store.artifact(artifactId)?.takeIf { it.jobId == jobId }
            ?: throw ApiException("ARTIFACT_NOT_FOUND", "Artifact not found.", HttpStatus.NOT_FOUND)
        val bytes = store.artifactBytes(artifactId) ?: throw ApiException("ARTIFACT_NOT_FOUND", "Artifact not found.", HttpStatus.NOT_FOUND)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${meta.filename.replace("\"", "")}\"")
            .body(ByteArrayResource(bytes))
    }
}
