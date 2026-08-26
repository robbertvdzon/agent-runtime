package nl.vdzon.agentruntime.server.monitor

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import nl.vdzon.agentruntime.contracts.JobStatus
import nl.vdzon.agentruntime.server.jobs.JobStore
import nl.vdzon.agentruntime.server.workers.WorkerStore
import org.springframework.stereotype.Component

@Component
class RuntimeMetrics(jobs: JobStore, workers: WorkerStore, registry: MeterRegistry) {
    init {
        Gauge.builder("agent_runtime_jobs_waiting_environment_keys") {
            val online = workers.listWorkers().filter { it.status == "ONLINE" }
            jobs.list(null, null, 5_000).count { job ->
                job.view.status in setOf(JobStatus.QUEUED, JobStatus.WAITING_FOR_WORKER) &&
                    job.request.environmentKeys.isNotEmpty() &&
                    online.none { worker -> job.request.environmentKeys.all { it in worker.availableEnvironmentKeys } }
            }.toDouble()
        }.description("Jobs waiting without an online worker that has all required environment keys").register(registry)
    }
}
