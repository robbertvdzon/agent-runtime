package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.vdzon.agentruntime.contracts.v2.CreateMockFixtureRequest
import nl.vdzon.agentruntime.contracts.v2.ExecutionMode
import nl.vdzon.agentruntime.contracts.v2.MockFixtureView
import nl.vdzon.agentruntime.contracts.v2.UsageQuality
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import nl.vdzon.agentruntime.server.config.RuntimeEnvironment
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@Repository
class V2MockFixtureStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun create(request: CreateMockFixtureRequest): MockFixtureView {
        validateResponse(request)
        val id = UUID.randomUUID().toString()
        try {
            jdbc.update(
                """INSERT INTO runtime_v2_mock_fixture(id,tenant_id,idempotency_key,result_json,output_sequence_json,error_code,error_message,delay_millis,output_artifact_names_json,repository_result_json,verification_result_json,created_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, request.tenantId, request.idempotencyKey, request.result?.toString(),
                request.outputSequence.takeIf(List<String>::isNotEmpty)?.let(mapper::writeValueAsString),
                request.errorCode, request.errorMessage, request.delayMillis,
                mapper.writeValueAsString(request.outputArtifactNames), request.repositoryResult?.let(mapper::writeValueAsString),
                request.verificationResult?.let(mapper::writeValueAsString), V2JobStore.utc(Instant.now()),
            )
        } catch (_: DuplicateKeyException) {
            throw ApiException("MOCK_FIXTURE_CONFLICT", "A fixture already exists for this exact tenant and idempotency key.", HttpStatus.CONFLICT)
        }
        return find(id)!!
    }

    fun list(): List<MockFixtureView> = jdbc.query(
        "SELECT * FROM runtime_v2_mock_fixture WHERE consumed_by_job_id IS NULL ORDER BY created_at,id",
        rowMapper(),
    )

    fun matching(job: StoredV2Job): MockFixtureView? = jdbc.query(
        "SELECT * FROM runtime_v2_mock_fixture WHERE tenant_id=? AND idempotency_key=? AND consumed_by_job_id IS NULL ORDER BY created_at,id",
        rowMapper(), job.view.tenantId, job.view.idempotencyKey,
    ).firstOrNull()

    fun consume(id: String, jobId: String): Boolean = jdbc.update(
        "UPDATE runtime_v2_mock_fixture SET consumed_by_job_id=?,consumed_at=? WHERE id=? AND consumed_by_job_id IS NULL",
        jobId, V2JobStore.utc(Instant.now()), id,
    ) == 1

    fun delete(id: String) { jdbc.update("DELETE FROM runtime_v2_mock_fixture WHERE id=?", id) }
    fun clear() { jdbc.update("DELETE FROM runtime_v2_mock_fixture") }

    private fun find(id: String): MockFixtureView? = jdbc.query("SELECT * FROM runtime_v2_mock_fixture WHERE id=?", rowMapper(), id).firstOrNull()

    private fun rowMapper() = org.springframework.jdbc.core.RowMapper<MockFixtureView> { rs, _ ->
        MockFixtureView(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("idempotency_key"),
            rs.getString("result_json")?.let(mapper::readTree),
            rs.getString("output_sequence_json")?.let { mapper.readValue(it, mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)) as List<String> } ?: emptyList(),
            rs.getString("error_code"), rs.getString("error_message"), rs.getLong("delay_millis"),
            mapper.readValue(rs.getString("output_artifact_names_json"), mapper.typeFactory.constructCollectionType(Set::class.java, String::class.java)) as Set<String>,
            rs.getString("repository_result_json")?.let { mapper.readValue(it, nl.vdzon.agentruntime.contracts.v2.RepositoryResult::class.java) },
            rs.getString("verification_result_json")?.let { mapper.readValue(it, nl.vdzon.agentruntime.contracts.v2.VerificationResult::class.java) },
            V2JobStore.instant(rs.getObject("created_at")),
        )
    }

    private fun validateResponse(request: CreateMockFixtureRequest) {
        val responseCount = listOf(request.result != null, request.outputSequence.isNotEmpty(), request.errorCode != null).count { it }
        if (responseCount != 1) throw ApiException("INVALID_MOCK_FIXTURE", "Exactly one of result, outputSequence or errorCode is required.", HttpStatus.BAD_REQUEST)
        if (request.errorCode == null && request.errorMessage != null) throw ApiException("INVALID_MOCK_FIXTURE", "errorMessage requires errorCode.", HttpStatus.BAD_REQUEST)
    }
}

@Service
class V2TargetedMockExecutor(
    private val properties: RuntimeProperties,
    private val fixtures: V2MockFixtureStore,
    private val jobs: V2JobStore,
    private val jobService: V2JobService,
    private val uploads: V2UploadService,
    private val objects: V2ObjectStore,
    private val mapper: ObjectMapper,
) {
    @Scheduled(fixedDelay = 500)
    @Synchronized
    @Transactional
    fun execute() {
        if (properties.environment == RuntimeEnvironment.PRODUCTION) return
        val job = jobs.queued().firstOrNull { it.view.execution.mode == ExecutionMode.MOCK } ?: return
        val fixture = fixtures.matching(job)
        if (fixture == null) {
            failWithoutFixture(job)
            return
        }
        if (!fixtures.consume(fixture.id, job.view.id)) return
        if (fixture.delayMillis > 0) Thread.sleep(fixture.delayMillis)
        val fixtureErrorCode = fixture.errorCode
        if (fixtureErrorCode != null) {
            val attempt = attempt(job)
            jobs.failAttempt(job, attempt.view.id, fixtureErrorCode, fixture.errorMessage ?: "Prepared mock failure.", false)
            return
        }
        val candidates = fixture.outputSequence.ifEmpty { listOf(mapper.writeValueAsString(fixture.result)) }
        for (candidate in candidates) {
            val current = jobs.find(job.view.id) ?: return
            if (current.view.status !in setOf(nl.vdzon.agentruntime.contracts.v2.JobStatus.QUEUED, nl.vdzon.agentruntime.contracts.v2.JobStatus.WAITING_FOR_WORKER)) return
            val attempt = attempt(current)
            val result = runCatching { mapper.readTree(candidate) }.getOrNull()
            if (result == null) {
                jobs.rejectOutput(jobs.find(job.view.id)!!, attempt.view.id, "MODEL_OUTPUT_NOT_JSON", "Prepared mock output is not JSON.")
                continue
            }
            val outputIds = createArtifacts(current, attempt.view.id, fixture.outputArtifactNames)
            val fresh = jobs.find(job.view.id)!!
            val requestedVerificationFailure = fixture.verificationResult?.status in setOf(
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.FAILED,
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.CONFIG_MISSING,
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.CONFIG_INVALID,
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.TIMEOUT,
            )
            val repositoryResult = if (requestedVerificationFailure) null else fixture.repositoryResult ?: deterministicRepositoryResult(fresh)
            val verificationResult = fixture.verificationResult ?: if (repositoryResult?.publicationStatus == nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationStatus.NO_CHANGES) null else deterministicVerificationResult(fresh)
            val verificationFailure = verificationResult?.status in setOf(
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.FAILED,
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.CONFIG_MISSING,
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.CONFIG_INVALID,
                nl.vdzon.agentruntime.contracts.v2.VerificationStatus.TIMEOUT,
            )
            val errors = jobService.validateResult(fresh, result, outputIds) +
                (if (verificationFailure) emptyList() else jobService.validateRepositoryResult(fresh, repositoryResult)) +
                jobService.validateVerificationResult(fresh, verificationResult, repositoryResult)
            if (errors.isNotEmpty()) {
                uploads.discardAttemptOutputs(job.view.id, attempt.view.id)
                jobs.rejectOutput(fresh, attempt.view.id, if (errors.any { it.code == "required" }) "MISSING_REQUIRED_ARTIFACT" else "MODEL_OUTPUT_SCHEMA_INVALID", errors.joinToString("; ") { it.message })
                continue
            }
            if (verificationFailure) {
                val verification = requireNotNull(verificationResult)
                val code = when (verification.status) {
                    nl.vdzon.agentruntime.contracts.v2.VerificationStatus.CONFIG_MISSING -> "VERIFICATION_CONFIG_MISSING"
                    nl.vdzon.agentruntime.contracts.v2.VerificationStatus.CONFIG_INVALID -> "VERIFICATION_CONFIG_INVALID"
                    nl.vdzon.agentruntime.contracts.v2.VerificationStatus.TIMEOUT -> "VERIFICATION_TIMEOUT"
                    else -> "VERIFICATION_FAILED"
                }
                jobs.completeWithVerificationFailure(job.view.id, attempt.view.id, result, verification, UsageQuality.MOCK, code, "Prepared mock verification failure.")
            } else jobs.complete(job.view.id, attempt.view.id, result, repositoryResult, verificationResult, UsageQuality.MOCK)
            return
        }
    }

    private fun deterministicRepositoryResult(job: StoredV2Job): nl.vdzon.agentruntime.contracts.v2.RepositoryResult? {
        val checkout = job.request.repositoryCheckout ?: return null
        val checkoutSha = MessageDigest.getInstance("SHA-1").digest("${job.view.id}:checkout".toByteArray()).joinToString("") { "%02x".format(it) }
        return when (checkout.publicationMode) {
            nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationMode.NONE -> nl.vdzon.agentruntime.contracts.v2.RepositoryResult(
                checkout.alias, checkout.branch, checkoutSha, nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationStatus.NONE,
            )
            nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationMode.COMMIT_AND_PUSH -> nl.vdzon.agentruntime.contracts.v2.RepositoryResult(
                checkout.alias, checkout.branch, checkoutSha, nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationStatus.PUSHED,
                MessageDigest.getInstance("SHA-1").digest("${job.view.id}:commit".toByteArray()).joinToString("") { "%02x".format(it) },
                "1 file changed",
            )
        }
    }

    private fun deterministicVerificationResult(job: StoredV2Job): nl.vdzon.agentruntime.contracts.v2.VerificationResult? {
        if (job.request.verification?.mode != nl.vdzon.agentruntime.contracts.v2.VerificationMode.REPOSITORY_CONFIG) return null
        return nl.vdzon.agentruntime.contracts.v2.VerificationResult(
            nl.vdzon.agentruntime.contracts.v2.VerificationStatus.PASSED,
            1,
            1,
            listOf(
                nl.vdzon.agentruntime.contracts.v2.VerificationCommandResult(
                    "mock-verification", listOf("mock", "verify"),
                    nl.vdzon.agentruntime.contracts.v2.VerificationCommandStatus.PASSED, 0, 1, "Mock verification passed.",
                ),
            ),
        )
    }

    private fun failWithoutFixture(job: StoredV2Job) {
        val attempt = attempt(job)
        jobs.failAttempt(job, attempt.view.id, "NO_MOCK_RESPONSE_CONFIGURED", "No targeted mock fixture matches this tenant and idempotency key.", false)
    }

    private fun attempt(job: StoredV2Job): StoredV2Attempt {
        val now = Instant.now()
        return jobs.createAttempt(job, "server-mock", "server", UUID.randomUUID().toString() + UUID.randomUUID(), now.plusSeconds(60), now.plusSeconds(job.request.executionTimeoutSeconds.toLong()))
    }

    private fun createArtifacts(job: StoredV2Job, attemptId: String, names: Set<String>): Set<String> {
        val unknown = names - job.request.output.artifacts.map { it.name }.toSet()
        if (unknown.isNotEmpty()) {
            jobs.failAttempt(job, attemptId, "UNDECLARED_OUTPUT", "Mock fixture names undeclared output artifacts.", false)
            return emptySet()
        }
        return names.sorted().mapIndexed { index, name ->
            val declaration = job.request.output.artifacts.first { it.name == name }
            val mimeType = preferredMimeType(declaration.mimeTypes)
            val bytes = fixtureBytes(name, mimeType)
            if (declaration.maxBytes?.let { bytes.size > it } == true) throw ApiException("OUTPUT_TOO_LARGE", "Mock artifact exceeds its declaration.", HttpStatus.UNPROCESSABLE_ENTITY)
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val upload = uploads.reserveOutput(job.view.tenantId, job.view.id, attemptId, name, "$name.${extension(mimeType)}", mimeType, bytes.size.toLong(), sha)
            uploads.append(job.view.tenantId, upload.uploadId, 0, ByteArrayInputStream(bytes), "OUTPUT")
            val ready = uploads.complete(job.view.tenantId, upload.uploadId, "OUTPUT")
            objects.link(job.view.id, ready.objectId, "OUTPUT", null, name, index)
            ready.objectId
        }.toSet()
    }

    private fun preferredMimeType(types: Set<String>): String = listOf("image/png", "application/json", "text/markdown", "text/plain").firstOrNull(types::contains) ?: types.sorted().first()
    private fun extension(mimeType: String) = when (mimeType) { "image/png" -> "png"; "application/json" -> "json"; "text/markdown" -> "md"; else -> "txt" }
    private fun fixtureBytes(name: String, mimeType: String): ByteArray = when (mimeType) {
        "image/png" -> Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        "application/json" -> mapper.writeValueAsBytes(mapOf("fixture" to name))
        else -> "deterministic mock artifact: $name\n".toByteArray()
    }
}

@RestController
@RequestMapping("/v2/test-control/mocks")
class V2MockFixtureController(private val properties: RuntimeProperties, private val fixtures: V2MockFixtureStore) {
    @GetMapping fun list(request: HttpServletRequest): List<MockFixtureView> { requireAvailable(request); return fixtures.list() }
    @PostMapping fun create(@Valid @RequestBody body: CreateMockFixtureRequest, request: HttpServletRequest): MockFixtureView {
        requireAvailable(request)
        if (body.tenantId !in properties.consumerTokens().keys) throw ApiException("UNKNOWN_TENANT", "Mock fixture tenant is unknown.", HttpStatus.BAD_REQUEST)
        return fixtures.create(body)
    }
    @DeleteMapping("/{id}") fun delete(@PathVariable id: String, request: HttpServletRequest) { requireAvailable(request); fixtures.delete(id) }
    @DeleteMapping fun clear(request: HttpServletRequest) { requireAvailable(request); fixtures.clear() }

    private fun requireAvailable(request: HttpServletRequest) {
        if (properties.environment == RuntimeEnvironment.PRODUCTION) throw ApiException("NOT_FOUND", "Not found.", HttpStatus.NOT_FOUND)
        if (ApiSecurity.identity(request).role != PrincipalRole.TEST_CONTROL) throw ApiException("FORBIDDEN", "Test-control credentials are required.", HttpStatus.FORBIDDEN)
    }
}
