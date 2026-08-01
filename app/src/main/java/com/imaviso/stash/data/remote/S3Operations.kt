package com.imaviso.stash.data.remote

import com.imaviso.stash.data.model.S3Bucket
import com.imaviso.stash.data.model.S3Object
import java.io.File
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Port at the S3 transport seam: every bucket/object operation callers use.
 *
 * Keys are plain strings at this seam; key grammar (join, folder rule,
 * encoding) stays owned by `ObjectKey` inside the adapters. Two adapters:
 * [S3Service] (AWS SDK, production) and `FakeS3Operations` (in-memory, tests).
 *
 * Not in the port: `initialize`/lifecycle of the shared singleton and
 * `testConnection` (pre-binding credential probing) remain on [S3Service].
 */
interface S3Operations {
    // ==================== BUCKETS ====================

    suspend fun listBuckets(): Result<List<S3Bucket>>

    suspend fun createBucket(name: String): Result<Unit>

    suspend fun deleteBucket(name: String): Result<Unit>

    // ==================== LISTING ====================

    /** One listing level: direct files plus folder entries (trailing '/'). */
    suspend fun listObjects(
        bucketName: String,
        prefix: String = "",
        delimiter: String = "/",
    ): Result<List<S3Object>>

    /** Flat list of every object under [prefix] (no delimiter). */
    suspend fun listObjectsRecursive(
        bucketName: String,
        prefix: String = "",
    ): Result<List<S3Object>>

    // ==================== UPLOAD ====================

    suspend fun uploadObject(
        bucketName: String,
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit>

    /** Upload a file already on disk (worker temp files). */
    suspend fun uploadObjectFromFile(
        bucketName: String,
        key: String,
        file: File,
        contentType: String = "application/octet-stream",
    ): Result<Unit>

    /**
     * Upload from a stream via a cache temp file - no whole-file RAM buffering.
     * [onProgress] gets (bytesSent, totalBytes, phase) with phase
     * "preparing" (temp-file copy, granular), "uploading", or "complete".
     */
    suspend fun uploadObjectFromStream(
        bucketName: String,
        key: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String = "application/octet-stream",
        cacheDir: File,
        onProgress: (suspend (bytesSent: Long, totalBytes: Long, phase: String) -> Unit)? = null,
    ): Result<Unit>

    // ==================== DOWNLOAD ====================

    /** Whole object in memory - callers cap size beforehand. */
    suspend fun downloadObject(
        bucketName: String,
        key: String,
    ): Result<ByteArray>

    /**
     * Stream an object to [destFile]. Deletes [destFile] on failure.
     * [onProgress] gets (bytesWritten, totalBytes) roughly every 100KB.
     */
    suspend fun downloadObjectToFile(
        bucketName: String,
        key: String,
        destFile: File,
        expectedSize: Long,
        onProgress: (suspend (bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<Unit>

    // ==================== OBJECT MUTATIONS ====================

    /** Zero-byte object with folder semantics (trailing '/'). */
    suspend fun createFolder(
        bucketName: String,
        folderPath: String,
    ): Result<Unit>

    suspend fun copyObject(
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String,
    ): Result<Unit>

    /** Idempotent: deleting a missing key succeeds. */
    suspend fun deleteObject(
        bucketName: String,
        key: String,
    ): Result<Unit>

    suspend fun deleteObjects(
        bucketName: String,
        keys: List<String>,
    ): Result<Unit>

    suspend fun objectExists(
        bucketName: String,
        key: String,
    ): Result<Boolean>

    // ==================== PRESIGNING ====================

    /**
     * Time-limited GET URL (sharing, streaming preview). Path-style endpoints
     * are signed manually; virtual-hosted style uses the SDK presigner.
     */
    suspend fun getPresignedUrl(
        bucketName: String,
        key: String,
        expiresIn: Duration = 1.hours,
    ): Result<String>

    /** Release the underlying client. No-op on adapters without one. */
    fun close()
}
