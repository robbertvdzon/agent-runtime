package nl.vdzon.agentruntime.server.v2

import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Applies retention without deleting accounting, job or attempt records. */
@Service
class V2ContentCleanup(
    private val properties: RuntimeProperties,
    private val jdbc: JdbcTemplate,
    private val objects: V2ObjectStore,
    private val blobs: FilesystemBlobStore,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT15M")
    fun run() {
        objects.expiredUploads(Instant.now()).forEach { upload ->
            runCatching {
                blobs.delete(upload.blobKey)
                objects.deleteUploadMetadata(upload.view.uploadId, upload.objectId)
            }.onFailure { logger.warn("Could not remove expired upload {}", upload.view.uploadId, it) }
        }
        expiredJobIds().forEach { id ->
            runCatching { deleteJobContent(id) }.onFailure { logger.warn("Could not remove retained content for job {}", id, it) }
        }
        jdbc.update(
            "DELETE FROM runtime_v2_event WHERE event_type='LOG_MESSAGE' AND created_at<?",
            V2JobStore.utc(Instant.now().minusSeconds(properties.monitorLogRetentionDays * 86_400)),
        )
    }

    private fun expiredJobIds(): List<String> = jdbc.query(
        """SELECT id FROM runtime_v2_job WHERE content_deleted_at IS NULL AND completed_at IS NOT NULL AND
            (content_delete_requested_at IS NOT NULL OR
             (status='SUCCEEDED' AND completed_at<?) OR
             (status IN ('FAILED','CANCELLED') AND completed_at<?))""",
        { rs, _ -> rs.getString(1) },
        V2JobStore.utc(Instant.now().minusSeconds(properties.successfulContentRetentionDays * 86_400)),
        V2JobStore.utc(Instant.now().minusSeconds(properties.failedContentRetentionDays * 86_400)),
    )

    @Transactional
    fun deleteJobContent(jobId: String) {
        val rows = jdbc.query(
            """SELECT o.id,o.blob_key FROM runtime_v2_object o JOIN runtime_v2_job_object jo ON jo.object_id=o.id
                WHERE jo.job_id=? AND o.deleted_at IS NULL""",
            { rs, _ -> rs.getString("id") to rs.getString("blob_key") }, jobId,
        )
        rows.forEach { (id, key) ->
            blobs.delete(key)
            jdbc.update("UPDATE runtime_v2_object SET state='DELETED',deleted_at=? WHERE id=?", V2JobStore.utc(Instant.now()), id)
        }
        jdbc.update("DELETE FROM runtime_v2_event WHERE job_id=? AND event_type='LOG_MESSAGE'", jobId)
        val now = V2JobStore.utc(Instant.now())
        jdbc.update("UPDATE runtime_v2_job SET content_deleted_at=?,updated_at=? WHERE id=?", now, now, jobId)
    }
}
