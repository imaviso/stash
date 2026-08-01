package com.imaviso.stash.data.remote

import com.imaviso.stash.data.model.ObjectKey
import com.imaviso.stash.data.model.S3Bucket
import com.imaviso.stash.data.model.S3Object
import java.io.File
import java.io.InputStream
import kotlin.time.Duration

/**
 * In-memory test adapter for the S3 transport seam ([S3Operations]).
 *
 * Buckets are `MutableMap<key, StoredObject>`. Folders are modeled exactly
 * like S3: zero-byte objects whose key has folder semantics (trailing '/').
 * Presigned URLs are synthetic strings containing bucket and key.
 */
class FakeS3Operations : S3Operations {
    data class StoredObject(
        val data: ByteArray,
        val contentType: String,
    )

    private val buckets = mutableMapOf<String, MutableMap<String, StoredObject>>()

    // ==================== TEST HELPERS (not part of the port) ====================

    fun seedBucket(name: String) {
        buckets.getOrPut(name) { mutableMapOf() }
    }

    fun seedObject(
        bucket: String,
        key: String,
        data: ByteArray = ByteArray(0),
        contentType: String = "application/octet-stream",
    ) {
        buckets.getOrPut(bucket) { mutableMapOf() }[key] = StoredObject(data, contentType)
    }

    fun seedFolder(
        bucket: String,
        key: String,
    ) = seedObject(bucket, ObjectKey(key).asFolder().key)

    fun keys(bucket: String): Set<String> = buckets[bucket]?.keys?.toSet() ?: emptySet()

    fun hasObject(
        bucket: String,
        key: String,
    ): Boolean = buckets[bucket]?.containsKey(key) == true

    // ==================== BUCKETS ====================

    override suspend fun listBuckets(): Result<List<S3Bucket>> =
        runCatching { buckets.keys.sorted().map { S3Bucket(name = it) } }

    override suspend fun createBucket(name: String): Result<Unit> =
        runCatching {
            if (buckets.containsKey(name)) throw IllegalStateException("Bucket already exists: $name")
            seedBucket(name)
        }

    override suspend fun deleteBucket(name: String): Result<Unit> =
        runCatching {
            val objects = buckets[name] ?: throw NoSuchElementException("Bucket not found: $name")
            if (objects.isNotEmpty()) throw IllegalStateException("Bucket not empty: $name")
            buckets.remove(name)
            Unit
        }

    // ==================== LISTING ====================

    override suspend fun listObjects(
        bucketName: String,
        prefix: String,
        delimiter: String,
    ): Result<List<S3Object>> =
        runCatching {
            val objects = bucketOrThrow(bucketName)
            val result = mutableListOf<S3Object>()
            val seenFolders = mutableSetOf<String>()

            for ((key, obj) in objects) {
                if (!key.startsWith(prefix) || key == prefix) continue
                val remainder = key.removePrefix(prefix)
                val slash = remainder.indexOf('/')

                if (delimiter == "/" && slash >= 0) {
                    // Everything up to and including the delimiter is a common prefix
                    val folderKey = prefix + remainder.substring(0, slash + 1)
                    if (seenFolders.add(folderKey)) {
                        result.add(S3Object(key = folderKey, size = 0))
                    }
                } else {
                    // Direct file; zero-byte folder markers are filtered like S3Service
                    val isFolderMarker = ObjectKey(key).isFolder && obj.data.isEmpty()
                    if (!isFolderMarker) {
                        result.add(S3Object(key = key, size = obj.data.size.toLong()))
                    }
                }
            }
            result.toList()
        }

    override suspend fun listObjectsRecursive(
        bucketName: String,
        prefix: String,
    ): Result<List<S3Object>> =
        runCatching {
            bucketOrThrow(bucketName)
                .filterKeys { it.startsWith(prefix) && it != prefix }
                .map { (key, obj) -> S3Object(key = key, size = obj.data.size.toLong()) }
        }

    // ==================== UPLOAD ====================

    override suspend fun uploadObject(
        bucketName: String,
        key: String,
        data: ByteArray,
        contentType: String,
    ): Result<Unit> =
        runCatching {
            bucketOrThrow(bucketName)[key] = StoredObject(data, contentType)
            Unit
        }

    override suspend fun uploadObjectFromFile(
        bucketName: String,
        key: String,
        file: File,
        contentType: String,
    ): Result<Unit> =
        runCatching {
            bucketOrThrow(bucketName)[key] = StoredObject(file.readBytes(), contentType)
            Unit
        }

    override suspend fun uploadObjectFromStream(
        bucketName: String,
        key: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String,
        cacheDir: File,
        onProgress: (suspend (Long, Long, String) -> Unit)?,
    ): Result<Unit> =
        runCatching {
            val data = inputStream.readBytes()
            onProgress?.invoke(data.size.toLong(), contentLength, "preparing")
            bucketOrThrow(bucketName)[key] = StoredObject(data, contentType)
            onProgress?.invoke(contentLength, contentLength, "uploading")
            onProgress?.invoke(contentLength, contentLength, "complete")
            Unit
        }

    // ==================== DOWNLOAD ====================

    override suspend fun downloadObject(
        bucketName: String,
        key: String,
    ): Result<ByteArray> = runCatching { objectOrThrow(bucketName, key).data }

    override suspend fun downloadObjectToFile(
        bucketName: String,
        key: String,
        destFile: File,
        expectedSize: Long,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ): Result<Unit> =
        runCatching {
            val obj = objectOrThrow(bucketName, key)
            destFile.writeBytes(obj.data)
            val total = if (expectedSize > 0) expectedSize else obj.data.size.toLong()
            onProgress?.invoke(obj.data.size.toLong(), total)
            Unit
        }.onFailure {
            // Same contract as the production adapter: no partial file left behind
            destFile.delete()
        }

    // ==================== OBJECT MUTATIONS ====================

    override suspend fun createFolder(
        bucketName: String,
        folderPath: String,
    ): Result<Unit> =
        runCatching {
            bucketOrThrow(bucketName)[ObjectKey(folderPath).asFolder().key] =
                StoredObject(ByteArray(0), "application/octet-stream")
            Unit
        }

    override suspend fun copyObject(
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String,
    ): Result<Unit> =
        runCatching {
            bucketOrThrow(destBucket)[destKey] = objectOrThrow(sourceBucket, sourceKey)
            Unit
        }

    override suspend fun deleteObject(
        bucketName: String,
        key: String,
    ): Result<Unit> =
        runCatching {
            // Idempotent like S3: deleting a missing key succeeds
            bucketOrThrow(bucketName).remove(key)
            Unit
        }

    override suspend fun deleteObjects(
        bucketName: String,
        keys: List<String>,
    ): Result<Unit> =
        runCatching {
            val objects = bucketOrThrow(bucketName)
            keys.forEach { objects.remove(it) }
            Unit
        }

    override suspend fun objectExists(
        bucketName: String,
        key: String,
    ): Result<Boolean> = runCatching { bucketOrThrow(bucketName).containsKey(key) }

    // ==================== PRESIGNING ====================

    override suspend fun getPresignedUrl(
        bucketName: String,
        key: String,
        expiresIn: Duration,
    ): Result<String> =
        runCatching {
            objectOrThrow(bucketName, key)
            "https://fake.s3/$bucketName/${ObjectKey(key).encoded}" +
                "?X-Amz-Expires=${expiresIn.inWholeSeconds}&X-Amz-Signature=fake"
        }

    override fun close() {
        // No client to release
    }

    // ==================== INTERNALS ====================

    private fun bucketOrThrow(name: String): MutableMap<String, StoredObject> =
        buckets[name] ?: throw NoSuchElementException("Bucket not found: $name")

    private fun objectOrThrow(
        bucket: String,
        key: String,
    ): StoredObject = bucketOrThrow(bucket)[key] ?: throw NoSuchElementException("No such key: $bucket/$key")
}
