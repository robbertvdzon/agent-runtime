package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationIntentStatus
import nl.vdzon.agentruntime.contracts.v2.RepositoryPublicationIntentView
import nl.vdzon.agentruntime.contracts.v2.VerificationResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

data class StoredRepositoryPublication(
    val view: RepositoryPublicationIntentView,
    val result: JsonNode,
    val outputObjectIds: Set<String>,
    val diffStat: String?,
    val verificationResult: VerificationResult?,
)

@Repository
class V2RepositoryPublicationStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun find(jobId: String): StoredRepositoryPublication? = jdbc.query(
        "SELECT * FROM runtime_v2_repository_publication WHERE job_id=?",
        rowMapper(), jobId,
    ).firstOrNull()

    @Synchronized
    fun prepare(
        jobId: String,
        attemptId: String,
        alias: String,
        branch: String,
        checkoutCommitSha: String,
        intendedCommitSha: String,
        diffStat: String?,
        result: JsonNode,
        outputObjectIds: Set<String>,
        verificationResult: VerificationResult?,
    ): StoredRepositoryPublication {
        val existing = find(jobId)
        if (existing != null) return existing
        val now = Instant.now()
        jdbc.update(
            """INSERT INTO runtime_v2_repository_publication(job_id,attempt_id,alias,branch_name,checkout_commit_sha,intended_commit_sha,diff_stat,result_json,output_object_ids_json,verification_result_json,status,prepared_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,'PREPARED',?)""",
            jobId, attemptId, alias, branch, checkoutCommitSha, intendedCommitSha, diffStat, result.toString(),
            mapper.writeValueAsString(outputObjectIds), verificationResult?.let(mapper::writeValueAsString), V2JobStore.utc(now),
        )
        return find(jobId)!!
    }

    fun markPushed(jobId: String, commitSha: String) {
        jdbc.update(
            "UPDATE runtime_v2_repository_publication SET status='PUSHED',pushed_at=? WHERE job_id=? AND intended_commit_sha=? AND status='PREPARED'",
            V2JobStore.utc(Instant.now()), jobId, commitSha,
        )
    }

    fun markFinalized(jobId: String) {
        jdbc.update(
            "UPDATE runtime_v2_repository_publication SET status='FINALIZED',finalized_at=? WHERE job_id=? AND status IN ('PREPARED','PUSHED')",
            V2JobStore.utc(Instant.now()), jobId,
        )
    }

    fun discard(jobId: String, commitSha: String): StoredRepositoryPublication? {
        val existing = find(jobId)?.takeIf { it.view.intendedCommitSha == commitSha && it.view.status != RepositoryPublicationIntentStatus.FINALIZED }
            ?: return null
        jdbc.update("DELETE FROM runtime_v2_repository_publication WHERE job_id=? AND intended_commit_sha=? AND status<>'FINALIZED'", jobId, commitSha)
        return existing
    }

    private fun rowMapper() = org.springframework.jdbc.core.RowMapper<StoredRepositoryPublication> { rs, _ ->
        val view = RepositoryPublicationIntentView(
            rs.getString("job_id"), rs.getString("attempt_id"), rs.getString("alias"), rs.getString("branch_name"),
            rs.getString("checkout_commit_sha"), rs.getString("intended_commit_sha"),
            RepositoryPublicationIntentStatus.valueOf(rs.getString("status")),
            V2JobStore.instant(rs.getObject("prepared_at")), rs.getObject("pushed_at")?.let(V2JobStore::instant),
        )
        val outputIds = mapper.readValue(
            rs.getString("output_object_ids_json"),
            mapper.typeFactory.constructCollectionType(Set::class.java, String::class.java),
        ) as Set<String>
        StoredRepositoryPublication(
            view, mapper.readTree(rs.getString("result_json")), outputIds, rs.getString("diff_stat"),
            rs.getString("verification_result_json")?.let { mapper.readValue(it, VerificationResult::class.java) },
        )
    }
}
