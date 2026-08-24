package nl.vdzon.agentruntime.server.workers

import nl.vdzon.agentruntime.contracts.*
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import nl.vdzon.agentruntime.server.jobs.JobService
import nl.vdzon.agentruntime.server.jobs.JobStore
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@Service
class ExecutionService(
    private val jobs: JobStore,
    private val workers: WorkerStore,
    private val jobService: JobService,
    private val properties: RuntimeProperties,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {
    private val random = SecureRandom()
    private val transactions = TransactionTemplate(transactionManager)

    fun register(request: WorkerRegistrationRequest): WorkerView {
        workers.register(request)
        return workers.listWorkers().first { it.workerId == request.workerId }
    }

    fun claim(workerId: String, request: ClaimRequest): ClaimedJob? {
        workers.heartbeat(workerId, request.bootId)
        val deadline = Instant.now().plusSeconds(request.waitSeconds.toLong())
        do {
            claimNow(workerId, request)?.let { return it }
            if (Instant.now().isBefore(deadline)) Thread.sleep(250)
        } while (Instant.now().isBefore(deadline))
        return null
    }

    @Synchronized
    fun claimNow(workerId: String, request: ClaimRequest): ClaimedJob? = transactions.execute {
        val candidate = jobs.queued().firstOrNull { job ->
            job.view.provider != Provider.MOCKED &&
                job.view.provider in request.providers &&
                (request.models.isEmpty() || job.view.model in request.models) &&
                requiredCapability(job.view.jobKind) in request.capabilities
        }
        jobs.queued().filter { it.view.provider != Provider.MOCKED }.forEach { jobs.markWaiting(it.view.id) }
        candidate ?: return@execute null
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val leaseUntil = Instant.now().plusSeconds(properties.leaseSeconds)
        val number = candidate.view.attemptCount + 1
        val attempt = workers.createAttempt(candidate.view.id, workerId, request.bootId, number, token, leaseUntil)
        jobs.markRunning(candidate.view.id, number)
        ClaimedJob(jobs.find(candidate.view.id)!!.view, attempt.id, token, leaseUntil, candidate.request)
    }

    fun heartbeat(jobId: String, workerId: String, request: HeartbeatRequest): HeartbeatResponse {
        workers.heartbeat(workerId, request.bootId)
        val attempt = authenticatedAttempt(jobId, request.attemptId, request.fencingToken, workerId)
        val job = jobs.find(jobId) ?: return HeartbeatResponse(false, false, true, null)
        if (attempt.status !in setOf("ACTIVE", "SUSPECTED")) return HeartbeatResponse(false, job.cancelRequested, true, null)
        if (attempt.status == "SUSPECTED" && attempt.recoveryUntil?.isBefore(Instant.now()) == true) {
            return HeartbeatResponse(false, job.cancelRequested, true, null)
        }
        val leaseUntil = Instant.now().plusSeconds(properties.leaseSeconds)
        workers.renew(attempt.id, leaseUntil)
        return HeartbeatResponse(true, job.cancelRequested, false, leaseUntil)
    }

    fun progress(jobId: String, workerId: String, request: ProgressRequest) {
        authenticatedAttempt(jobId, request.attemptId, request.fencingToken, workerId)
        jobs.progress(jobId, request.phase, request.percent, Redactor.clean(request.message))
    }

    @Transactional
    fun complete(jobId: String, workerId: String, request: CompleteAttemptRequest) {
        val attempt = authenticatedAttempt(jobId, request.attemptId, request.fencingToken, workerId)
        val job = jobs.find(jobId) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        if (job.cancelRequested) {
            workers.finish(attempt.id, "CANCELLED")
            jobs.markCancelled(jobId, "Worker acknowledged cancellation.")
            return
        }
        jobService.validateResult(job, request.result)
        workers.finish(attempt.id, "SUCCEEDED")
        jobs.complete(jobId, request.result, request.usage)
    }

    @Transactional
    fun fail(jobId: String, workerId: String, request: FailAttemptRequest) {
        val attempt = authenticatedAttempt(jobId, request.attemptId, request.fencingToken, workerId)
        val job = jobs.find(jobId) ?: throw ApiException("JOB_NOT_FOUND", "Job not found.", HttpStatus.NOT_FOUND)
        workers.finish(attempt.id, "FAILED")
        val message = Redactor.clean(request.message).orEmpty()
        if (job.cancelRequested) jobs.markCancelled(jobId, "Worker stopped after cancellation.")
        else if (request.retryable && job.view.attemptCount < job.view.maxAttempts) {
            jobs.retry(jobId, request.errorCode, message, retryAt(job.view.attemptCount))
        } else jobs.fail(jobId, request.errorCode, message)
    }

    fun authenticateArtifact(jobId: String, workerId: String, attemptId: String, token: String) {
        authenticatedAttempt(jobId, attemptId, token, workerId)
    }

    @Scheduled(fixedDelay = 15_000)
    fun reconcileLeases() {
        val recoveryUntil = Instant.now().plusSeconds(properties.recoverySeconds)
        workers.markExpiredActive(recoveryUntil).forEach {
            jobs.addEvent(it.jobId, "ATTEMPT_SUSPECTED", "SUSPECTED", "Heartbeat expired; the same worker may recover until $recoveryUntil.")
        }
        workers.abandonExpiredSuspected().forEach { attempt ->
            val job = jobs.find(attempt.jobId) ?: return@forEach
            if (job.view.status != JobStatus.RUNNING) return@forEach
            if (job.view.attemptCount < job.view.maxAttempts) {
                jobs.retry(job.view.id, "LEASE_ABANDONED", "Worker did not recover during the recovery window.", retryAt(job.view.attemptCount))
            } else jobs.fail(job.view.id, "LEASE_ABANDONED", "Worker did not recover and all attempts are exhausted.")
        }
    }

    private fun authenticatedAttempt(jobId: String, attemptId: String, token: String, workerId: String): AttemptRecord {
        val attempt = workers.findAttempt(attemptId)
            ?: throw ApiException("ATTEMPT_FENCED", "Attempt no longer exists.", HttpStatus.CONFLICT)
        if (attempt.jobId != jobId || attempt.workerId != workerId || !workers.validToken(attempt, token) || attempt.status !in setOf("ACTIVE", "SUSPECTED")) {
            throw ApiException("ATTEMPT_FENCED", "Attempt is not the current authorized execution.", HttpStatus.CONFLICT)
        }
        return attempt
    }

    private fun requiredCapability(kind: JobKind) = when (kind) {
        JobKind.APPLICATION_WORK -> "application-work"
        JobKind.REPOSITORY_WORK -> "repository-work"
    }

    private fun retryAt(attemptCount: Int): Instant {
        val delay = (30L * (1L shl (attemptCount - 1).coerceIn(0, 6))).coerceAtMost(1800)
        return Instant.now().plusSeconds(delay)
    }
}

object Redactor {
    private val bearer = Regex("(?i)bearer\\s+[a-z0-9._~+/-]+=*")
    private val keyValue = Regex("(?i)(password|token|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+")
    fun clean(value: String?): String? = value?.replace(bearer, "Bearer [REDACTED]")?.replace(keyValue, "$1=[REDACTED]")?.take(1000)
}
