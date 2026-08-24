package nl.vdzon.agentruntime.server.monitor

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.JobStatus
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import nl.vdzon.agentruntime.server.jobs.JobStore
import nl.vdzon.agentruntime.server.workers.WorkerStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class MonitorSummary(
    val generatedAt: Instant,
    val workers: Int,
    val onlineWorkers: Int,
    val jobsByStatus: Map<JobStatus, Int>,
    val oldestWaitingAt: Instant?,
)

@RestController
class AdminController(private val jobs: JobStore, private val workers: WorkerStore) {
    @GetMapping("/healthz") fun health() = mapOf("status" to "UP")

    @GetMapping("/v1/admin/summary")
    fun summary(request: HttpServletRequest): MonitorSummary {
        admin(request)
        val allJobs = jobs.list(null, null, 500)
        val allWorkers = workers.listWorkers()
        return MonitorSummary(
            Instant.now(), allWorkers.size, allWorkers.count { it.status == "ONLINE" },
            JobStatus.entries.associateWith { status -> allJobs.count { it.view.status == status } },
            allJobs.filter { it.view.status in setOf(JobStatus.QUEUED, JobStatus.WAITING_FOR_WORKER) }.minOfOrNull { it.view.createdAt },
        )
    }

    @PostMapping("/v1/admin/jobs/{id}/retry")
    fun retry(@PathVariable id: String, request: HttpServletRequest) {
        admin(request)
        val job = jobs.find(id) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        if (job.view.status !in setOf(JobStatus.FAILED, JobStatus.CANCELLED)) throw ApiException("RETRY_NOT_ALLOWED", "Only terminal failed or cancelled jobs can be retried.", HttpStatus.CONFLICT)
        jobs.resetForAdminRetry(id)
    }

    private fun admin(request: HttpServletRequest) {
        if (ApiSecurity.identity(request).role != PrincipalRole.ADMIN) throw ApiException("FORBIDDEN", "Administrator required.", HttpStatus.FORBIDDEN)
    }
}
