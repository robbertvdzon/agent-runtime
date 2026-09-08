package nl.vdzon.agentruntime.server.v2

import nl.vdzon.agentruntime.contracts.v2.ObjectView
import nl.vdzon.agentruntime.contracts.v2.UploadView
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StoredV2Object(
    val view: ObjectView,
    val tenantId: String,
    val purpose: String,
    val blobKey: String,
    val expiresAt: Instant,
    val deletedAt: Instant?,
)

data class StoredV2Upload(
    val view: UploadView,
    val tenantId: String,
    val objectId: String,
    val jobId: String?,
    val attemptId: String?,
    val logicalName: String?,
    val direction: String,
    val blobKey: String,
)

@Component
class FilesystemBlobStore(private val properties: RuntimeProperties) {
    val root: Path by lazy {
        Path.of(properties.objectStorePath).toAbsolutePath().normalize().also {
            Files.createDirectories(it.resolve("tmp"))
            Files.createDirectories(it.resolve("objects"))
        }
    }

    fun temporaryKey(uploadId: String) = "tmp/${safeId(uploadId)}.part"
    fun objectKey(objectId: String) = "objects/${safeId(objectId).take(2)}/${safeId(objectId)}"
    fun availableBytes(): Long = Files.getFileStore(root).usableSpace

    fun append(key: String, expectedOffset: Long, input: InputStream, maxBytes: Long): Long {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val actual = if (Files.exists(target)) Files.size(target) else 0L
        if (actual != expectedOffset) throw ApiException("UPLOAD_OFFSET_MISMATCH", "Expected offset $actual, not $expectedOffset.", HttpStatus.CONFLICT)
        var written = 0L
        try {
            Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (expectedOffset + written > maxBytes) throw ApiException("OBJECT_TOO_LARGE", "Upload exceeds its reserved size.", HttpStatus.PAYLOAD_TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: Exception) {
            truncate(target, expectedOffset)
            throw error
        }
        return written
    }

    fun truncate(key: String, size: Long) = truncate(resolve(key), size)

    fun finalize(temporaryKey: String, finalKey: String) {
        val source = resolve(temporaryKey)
        val target = resolve(finalKey)
        Files.createDirectories(target.parent)
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    fun size(key: String): Long = Files.size(resolve(key))
    fun open(key: String): InputStream = Files.newInputStream(resolve(key), StandardOpenOption.READ)
    fun openRange(key: String, start: Long): InputStream = Files.newByteChannel(resolve(key), StandardOpenOption.READ).let { channel ->
        channel.position(start)
        java.nio.channels.Channels.newInputStream(channel)
    }
    fun sha256(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(open(key), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    fun delete(key: String) { Files.deleteIfExists(resolve(key)) }

    private fun truncate(target: Path, size: Long) {
        if (!Files.exists(target)) return
        Files.newByteChannel(target, StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    private fun resolve(key: String): Path = root.resolve(key).normalize().also {
        if (!it.startsWith(root)) throw IllegalArgumentException("Invalid blob key")
    }
    private fun safeId(value: String): String = UUID.fromString(value).toString()
}

@Repository
class V2ObjectStore(private val jdbc: JdbcTemplate) {
    fun createInput(tenantId: String, id: String, filename: String, mimeType: String, size: Long, sha256: String, blobKey: String, expiresAt: Instant) {
        jdbc.update(
            """INSERT INTO runtime_v2_object(id,tenant_id,state,purpose,filename,mime_type,size_bytes,sha256,blob_key,created_at,expires_at)
                VALUES (?,?,'UPLOADING','INPUT',?,?,?,?,?,?,?)""",
            id, tenantId, filename, mimeType, size, sha256, blobKey, utc(Instant.now()), utc(expiresAt),
        )
    }

    fun createOutput(tenantId: String, id: String, filename: String, mimeType: String, size: Long, sha256: String, blobKey: String, expiresAt: Instant) {
        jdbc.update(
            """INSERT INTO runtime_v2_object(id,tenant_id,state,purpose,filename,mime_type,size_bytes,sha256,blob_key,created_at,expires_at)
                VALUES (?,?,'UPLOADING','OUTPUT',?,?,?,?,?,?,?)""",
            id, tenantId, filename, mimeType, size, sha256, blobKey, utc(Instant.now()), utc(expiresAt),
        )
    }

    fun createUpload(id: String, objectId: String, tenantId: String, jobId: String?, attemptId: String?, logicalName: String?, direction: String, expiresAt: Instant): StoredV2Upload {
        val now = Instant.now()
        jdbc.update(
            """INSERT INTO runtime_v2_upload(id,object_id,job_id,attempt_id,logical_name,direction,state,current_offset,created_at,updated_at,expires_at)
                VALUES (?,?,?,?,?,?,'UPLOADING',0,?,?,?)""",
            id, objectId, jobId, attemptId, logicalName, direction, utc(now), utc(now), utc(expiresAt),
        )
        return upload(id)!!
    }

    fun upload(id: String): StoredV2Upload? = jdbc.query("""SELECT u.*,o.tenant_id,o.blob_key,o.size_bytes FROM runtime_v2_upload u
        JOIN runtime_v2_object o ON o.id=u.object_id WHERE u.id=?""", { rs, _ ->
        StoredV2Upload(
            UploadView(rs.getString("id"), rs.getString("object_id"), rs.getString("state"), chunkSizeBytes = 8L * 1024 * 1024,
                uploadUrl = if (rs.getString("direction") == "INPUT") "/v2/uploads/${rs.getString("id")}" else "/v2/workers/_/jobs/${rs.getString("job_id")}/output-objects/${rs.getString("id")}",
                offset = rs.getLong("current_offset"), sizeBytes = rs.getLong("size_bytes"), expiresAt = instant(rs.getObject("expires_at"))),
            rs.getString("tenant_id"), rs.getString("object_id"), rs.getString("job_id"), rs.getString("attempt_id"),
            rs.getString("logical_name"), rs.getString("direction"), rs.getString("blob_key"),
        )
    }, id).firstOrNull()

    fun objectById(id: String): StoredV2Object? = jdbc.query("SELECT * FROM runtime_v2_object WHERE id=?", { rs, _ ->
        StoredV2Object(
            ObjectView(rs.getString("id"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("state"), instant(rs.getObject("created_at")), rs.getObject("ready_at")?.let(::instant)),
            rs.getString("tenant_id"), rs.getString("purpose"), rs.getString("blob_key"), instant(rs.getObject("expires_at")),
            rs.getObject("deleted_at")?.let(::instant),
        )
    }, id).firstOrNull()

    fun updateOffset(uploadId: String, expected: Long, updated: Long): Boolean = jdbc.update(
        "UPDATE runtime_v2_upload SET current_offset=?,updated_at=? WHERE id=? AND state='UPLOADING' AND current_offset=?",
        updated, utc(Instant.now()), uploadId, expected,
    ) == 1

    @Transactional
    fun markReady(uploadId: String, objectId: String, finalKey: String) {
        val now = utc(Instant.now())
        jdbc.update("UPDATE runtime_v2_upload SET state='READY',updated_at=? WHERE id=? AND state='UPLOADING'", now, uploadId)
        jdbc.update("UPDATE runtime_v2_object SET state='READY',blob_key=?,ready_at=? WHERE id=? AND state='UPLOADING'", finalKey, now, objectId)
    }

    fun deleteUploadMetadata(uploadId: String, objectId: String) {
        jdbc.update("DELETE FROM runtime_v2_upload WHERE id=?", uploadId)
        jdbc.update("DELETE FROM runtime_v2_object WHERE id=?", objectId)
    }

    fun markValidationFailed(uploadId: String, objectId: String, code: String) {
        jdbc.update("UPDATE runtime_v2_upload SET state='FAILED',updated_at=? WHERE id=?", utc(Instant.now()), uploadId)
        jdbc.update("UPDATE runtime_v2_object SET state='FAILED',validation_error_code=? WHERE id=?", code, objectId)
    }

    fun linkedToJob(jobId: String, objectId: String): StoredV2Object? = jdbc.query(
        "SELECT o.* FROM runtime_v2_object o JOIN runtime_v2_job_object jo ON jo.object_id=o.id WHERE jo.job_id=? AND o.id=? AND o.state='READY' AND o.deleted_at IS NULL",
        { rs, _ -> StoredV2Object(ObjectView(rs.getString("id"),rs.getString("filename"),rs.getString("mime_type"),rs.getLong("size_bytes"),rs.getString("sha256"),rs.getString("state"),instant(rs.getObject("created_at")),rs.getObject("ready_at")?.let(::instant)),rs.getString("tenant_id"),rs.getString("purpose"),rs.getString("blob_key"),instant(rs.getObject("expires_at")),rs.getObject("deleted_at")?.let(::instant)) }, jobId, objectId,
    ).firstOrNull()

    fun outputBytes(jobId: String): Long = jdbc.queryForObject("SELECT COALESCE(SUM(o.size_bytes),0) FROM runtime_v2_object o JOIN runtime_v2_job_object jo ON jo.object_id=o.id WHERE jo.job_id=? AND jo.direction='OUTPUT' AND o.state<>'DELETED'", Long::class.java, jobId) ?: 0
    fun isBound(objectId:String):Boolean=(jdbc.queryForObject("SELECT COUNT(*) FROM runtime_v2_job_object WHERE object_id=?",Int::class.java,objectId)?:0)>0

    fun link(jobId: String, objectId: String, direction: String, role: String?, logicalName: String, sequence: Int) {
        jdbc.update("INSERT INTO runtime_v2_job_object(job_id,object_id,direction,role,logical_name,sequence_number) VALUES (?,?,?,?,?,?)", jobId, objectId, direction, role, logicalName, sequence)
    }

    fun outputObjects(jobId: String): List<Pair<String, StoredV2Object>> = jdbc.query(
        """SELECT jo.logical_name,o.* FROM runtime_v2_job_object jo JOIN runtime_v2_object o ON o.id=jo.object_id
            WHERE jo.job_id=? AND jo.direction='OUTPUT' AND o.state='READY' AND o.deleted_at IS NULL ORDER BY jo.sequence_number,o.created_at""",
        { rs, _ -> rs.getString("logical_name") to StoredV2Object(ObjectView(rs.getString("id"),rs.getString("filename"),rs.getString("mime_type"),rs.getLong("size_bytes"),rs.getString("sha256"),rs.getString("state"),instant(rs.getObject("created_at")),instant(rs.getObject("ready_at"))),rs.getString("tenant_id"),rs.getString("purpose"),rs.getString("blob_key"),instant(rs.getObject("expires_at")),rs.getObject("deleted_at")?.let(::instant)) }, jobId,
    )

    fun inputObjects(jobId: String): List<Pair<String, StoredV2Object>> = jdbc.query(
        """SELECT jo.logical_name,o.* FROM runtime_v2_job_object jo JOIN runtime_v2_object o ON o.id=jo.object_id
            WHERE jo.job_id=? AND jo.direction='INPUT' AND o.state='READY' AND o.deleted_at IS NULL ORDER BY jo.sequence_number""",
        { rs, _ -> rs.getString("logical_name") to StoredV2Object(ObjectView(rs.getString("id"),rs.getString("filename"),rs.getString("mime_type"),rs.getLong("size_bytes"),rs.getString("sha256"),rs.getString("state"),instant(rs.getObject("created_at")),instant(rs.getObject("ready_at"))),rs.getString("tenant_id"),rs.getString("purpose"),rs.getString("blob_key"),instant(rs.getObject("expires_at")),rs.getObject("deleted_at")?.let(::instant)) }, jobId,
    )

    fun attemptOutputObjects(jobId:String,attemptId:String):List<StoredV2Object> = jdbc.query(
        """SELECT o.* FROM runtime_v2_object o JOIN runtime_v2_upload u ON u.object_id=o.id JOIN runtime_v2_job_object jo ON jo.object_id=o.id
            WHERE jo.job_id=? AND jo.direction='OUTPUT' AND u.attempt_id=? AND o.deleted_at IS NULL""",
        {rs,_->StoredV2Object(ObjectView(rs.getString("id"),rs.getString("filename"),rs.getString("mime_type"),rs.getLong("size_bytes"),rs.getString("sha256"),rs.getString("state"),instant(rs.getObject("created_at")),rs.getObject("ready_at")?.let(::instant)),rs.getString("tenant_id"),rs.getString("purpose"),rs.getString("blob_key"),instant(rs.getObject("expires_at")),rs.getObject("deleted_at")?.let(::instant))},jobId,attemptId)

    fun unlinkAndMarkDeleted(jobId:String,objectId:String) { jdbc.update("DELETE FROM runtime_v2_job_object WHERE job_id=? AND object_id=?",jobId,objectId);jdbc.update("UPDATE runtime_v2_object SET state='DELETED',deleted_at=? WHERE id=?",utc(Instant.now()),objectId) }

    fun expiredUploads(now: Instant): List<StoredV2Upload> = jdbc.query(
        """SELECT u.*,o.tenant_id,o.blob_key,o.size_bytes FROM runtime_v2_upload u JOIN runtime_v2_object o ON o.id=u.object_id
            WHERE u.state IN ('UPLOADING','READY') AND u.expires_at < ? AND NOT EXISTS
              (SELECT 1 FROM runtime_v2_job_object jo WHERE jo.object_id=u.object_id)""", { rs, _ -> StoredV2Upload(UploadView(rs.getString("id"),rs.getString("object_id"),rs.getString("state"),chunkSizeBytes=8L*1024*1024,uploadUrl="",offset=rs.getLong("current_offset"),sizeBytes=rs.getLong("size_bytes"),expiresAt=instant(rs.getObject("expires_at"))),rs.getString("tenant_id"),rs.getString("object_id"),rs.getString("job_id"),rs.getString("attempt_id"),rs.getString("logical_name"),rs.getString("direction"),rs.getString("blob_key")) }, utc(now),
    )

    companion object {
        fun utc(value: Instant) = value.atOffset(ZoneOffset.UTC)
        fun instant(value: Any): Instant = when (value) { is OffsetDateTime -> value.toInstant(); is java.sql.Timestamp -> value.toInstant(); else -> error("Unsupported timestamp $value") }
    }
}

@Service
class V2UploadService(
    private val properties: RuntimeProperties,
    private val store: V2ObjectStore,
    private val blobs: FilesystemBlobStore,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    fun get(tenantId:String,uploadId:String,direction:String):StoredV2Upload=requireUpload(tenantId,uploadId,direction)

    @Transactional
    fun reserveInput(tenantId: String, filename: String, mimeType: String, size: Long, sha256: String): UploadView {
        validateReservation(size)
        val objectId = UUID.randomUUID().toString()
        val uploadId = UUID.randomUUID().toString()
        val expiry = Instant.now().plusSeconds(properties.uploadRetentionHours * 3600)
        store.createInput(tenantId, objectId, filename, mimeType, size, sha256, blobs.temporaryKey(uploadId), expiry)
        return store.createUpload(uploadId, objectId, tenantId, null, null, null, "INPUT", expiry).view
    }

    @Transactional
    fun reserveOutput(tenantId: String, jobId: String, attemptId: String, name: String, filename: String, mimeType: String, size: Long, sha256: String): UploadView {
        validateReservation(size)
        if (store.outputBytes(jobId) + size > properties.jobOutputMaxBytes) throw ApiException("JOB_OUTPUT_TOO_LARGE", "Output objects exceed the 5 GiB job limit.", HttpStatus.PAYLOAD_TOO_LARGE)
        val objectId = UUID.randomUUID().toString()
        val uploadId = UUID.randomUUID().toString()
        val expiry = Instant.now().plusSeconds(properties.uploadRetentionHours * 3600)
        store.createOutput(tenantId, objectId, filename, mimeType, size, sha256, blobs.temporaryKey(uploadId), expiry)
        return store.createUpload(uploadId, objectId, tenantId, jobId, attemptId, name, "OUTPUT", expiry).view
    }

    fun append(tenantId: String, uploadId: String, offset: Long, input: InputStream, expectedDirection: String): Long = synchronized(locks.computeIfAbsent(uploadId) { Any() }) {
        val upload = requireUpload(tenantId, uploadId, expectedDirection)
        if (upload.view.state != "UPLOADING") throw ApiException("UPLOAD_NOT_ACTIVE", "Upload is not active.", HttpStatus.CONFLICT)
        if (upload.view.offset != offset) throw ApiException("UPLOAD_OFFSET_MISMATCH", "Expected offset ${upload.view.offset}, not $offset.", HttpStatus.CONFLICT)
        val written = blobs.append(upload.blobKey, offset, input, upload.view.sizeBytes)
        val next = offset + written
        if (!store.updateOffset(uploadId, offset, next)) {
            blobs.truncate(upload.blobKey, offset)
            throw ApiException("UPLOAD_OFFSET_MISMATCH", "Upload changed concurrently.", HttpStatus.CONFLICT)
        }
        next
    }

    fun complete(tenantId: String, uploadId: String, expectedDirection: String): ObjectView = synchronized(locks.computeIfAbsent(uploadId) { Any() }) {
        val upload = requireUpload(tenantId, uploadId, expectedDirection)
        val objectRecord = store.objectById(upload.objectId) ?: throw ApiException("NOT_FOUND", "Object not found.", HttpStatus.NOT_FOUND)
        if (objectRecord.view.state == "READY") return@synchronized objectRecord.view
        if (upload.view.offset != upload.view.sizeBytes || !runCatching { blobs.size(upload.blobKey) == upload.view.sizeBytes }.getOrDefault(false)) {
            throw ApiException("UPLOAD_INCOMPLETE", "Uploaded size does not match the reservation.", HttpStatus.UNPROCESSABLE_ENTITY)
        }
        if (blobs.sha256(upload.blobKey) != objectRecord.view.sha256) {
            store.markValidationFailed(uploadId, upload.objectId, "SHA256_MISMATCH")
            blobs.delete(upload.blobKey)
            throw ApiException("SHA256_MISMATCH", "Uploaded content does not match sha256.", HttpStatus.UNPROCESSABLE_ENTITY)
        }
        val finalKey = blobs.objectKey(upload.objectId)
        blobs.finalize(upload.blobKey, finalKey)
        store.markReady(uploadId, upload.objectId, finalKey)
        store.objectById(upload.objectId)!!.view
    }

    @Transactional
    fun deleteInput(tenantId: String, uploadId: String) {
        val upload = store.upload(uploadId) ?: return
        if (upload.tenantId != tenantId || upload.direction != "INPUT") throw ApiException("NOT_FOUND", "Upload not found.", HttpStatus.NOT_FOUND)
        if (upload.jobId != null || store.isBound(upload.objectId)) throw ApiException("UPLOAD_ALREADY_BOUND", "Upload is already bound to a job.", HttpStatus.CONFLICT)
        blobs.delete(upload.blobKey)
        store.deleteUploadMetadata(uploadId, upload.objectId)
    }

    @Transactional
    fun discardAttemptOutputs(jobId:String,attemptId:String) { store.attemptOutputObjects(jobId,attemptId).forEach{record->blobs.delete(record.blobKey);store.unlinkAndMarkDeleted(jobId,record.view.objectId)} }

    fun requireUpload(tenantId: String, uploadId: String, direction: String): StoredV2Upload {
        val upload = store.upload(uploadId) ?: throw ApiException("NOT_FOUND", "Upload not found.", HttpStatus.NOT_FOUND)
        if (upload.tenantId != tenantId || upload.direction != direction) throw ApiException("NOT_FOUND", "Upload not found.", HttpStatus.NOT_FOUND)
        if (upload.view.expiresAt.isBefore(Instant.now()) && upload.view.state == "UPLOADING") throw ApiException("UPLOAD_EXPIRED", "Upload expired.", HttpStatus.CONFLICT)
        return upload
    }

    private fun validateReservation(size: Long) {
        if (size !in 1..properties.objectMaxBytes) throw ApiException("OBJECT_TOO_LARGE", "An object may contain at most 2 GiB.", HttpStatus.PAYLOAD_TOO_LARGE)
        if (blobs.availableBytes() - size < properties.objectStoreMinFreeBytes) throw ApiException("OBJECT_STORE_LOW_SPACE", "New uploads are temporarily unavailable because free storage is below the safety threshold.", HttpStatus.SERVICE_UNAVAILABLE)
    }

}
