package com.imaviso.stash.data

import android.content.Context
import android.util.Log
import com.imaviso.stash.data.model.FileTypeStats
import com.imaviso.stash.data.model.ObjectKey
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.model.StorageStats
import com.imaviso.stash.data.remote.S3Operations
import com.imaviso.stash.util.DownloadsSaver
import java.io.File

/**
 * Outcome of a recursive folder download.
 */
data class FolderDownloadOutcome(
    val totalFiles: Int,
    val downloadedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
)

/**
 * Data-layer operator for multi-object S3 orchestration: recursive delete,
 * paste (copy/move), rename (copy + delete), storage stats, and recursive
 * folder downloads. Keeps S3 call sequencing out of the UI ViewModel.
 * Depends on the [S3Operations] port, not a concrete adapter.
 */
class ObjectOperations(
    private val s3: S3Operations,
) {
    companion object {
        private const val TAG = "ObjectOperations"
    }

    /**
     * Recursively delete all objects under [folderKey] plus the folder marker.
     * Returns failure if any batch delete fails.
     */
    suspend fun recursiveDeleteFolder(
        bucketName: String,
        folderKey: String,
    ): Result<Unit> =
        runCatching {
            val objects = s3.listObjectsRecursive(bucketName, folderKey).getOrThrow()
            if (objects.isNotEmpty()) {
                objects.chunked(1000).forEach { batch ->
                    s3.deleteObjects(bucketName, batch.map { it.key }).getOrThrow()
                }
            }
            // Delete the folder marker itself.
            s3.deleteObject(bucketName, folderKey)
        }

    /**
     * Recursively delete a folder with progress reporting.
     * [onProgress] gets (deleted so far, total) before each batch.
     */
    suspend fun deleteFolderRecursively(
        bucketName: String,
        folderKey: String,
        onProgress: (deleted: Int, total: Int) -> Unit,
    ): Result<Unit> =
        s3.listObjectsRecursive(bucketName, folderKey).map { objects ->
            // Delete in batches of 1000 (S3 limit)
            val totalCount = objects.size
            var deletedCount = 0

            objects.chunked(1000).forEach { batch ->
                onProgress(deletedCount, totalCount)
                s3.deleteObjects(bucketName, batch.map { it.key })
                deletedCount += batch.size
            }

            // Also delete the folder marker itself
            s3.deleteObject(bucketName, folderKey)
        }

    /**
     * Paste objects into [destBucket]:/[destPrefix] via server-side copy.
     * When [deleteSourceAfterCopy] is set (move), sources are deleted after copy.
     * [onProgress] gets (succeeded so far, total) after each object.
     * Returns the number of objects that failed to copy.
     */
    suspend fun pasteObjects(
        sourceBucket: String,
        destBucket: String,
        destPrefix: String,
        objects: List<S3Object>,
        deleteSourceAfterCopy: Boolean,
        onProgress: (successCount: Int, total: Int) -> Unit,
    ): Int {
        var successCount = 0
        var failCount = 0

        for (obj in objects) {
            val sourceKey = obj.key
            val destKey = ObjectKey(destPrefix).child(obj.fileName).key

            // Copy the object
            val copyResult =
                s3.copyObject(
                    sourceBucket = sourceBucket,
                    sourceKey = sourceKey,
                    destBucket = destBucket,
                    destKey = destKey,
                )

            if (copyResult.isSuccess) {
                // If it's a move operation, delete the source
                if (deleteSourceAfterCopy) {
                    s3.deleteObject(sourceBucket, sourceKey)
                }
                successCount++
            } else {
                failCount++
            }

            onProgress(successCount, objects.size)
        }

        return failCount
    }

    /**
     * Rename/move an object (copy + delete).
     */
    suspend fun renameObject(
        bucketName: String,
        oldKey: String,
        newKey: String,
    ): Result<Unit> =
        runCatching {
            s3.copyObject(bucketName, oldKey, bucketName, newKey).getOrThrow()
            s3.deleteObject(bucketName, oldKey).getOrThrow()
        }

    /**
     * Compute storage stats (counts + sizes by file type) for a prefix.
     */
    suspend fun computeStorageStats(
        bucketName: String,
        prefix: String,
    ): Result<StorageStats> =
        s3.listObjectsRecursive(bucketName, prefix).map { objects ->
            val files = objects.filter { !it.key.endsWith("/") }
            val folders = objects.filter { it.key.endsWith("/") }

            // Group by file type
            val byType = mutableMapOf<com.imaviso.stash.data.model.FileType, MutableList<S3Object>>()
            files.forEach { obj ->
                val type = obj.fileType
                byType.getOrPut(type) { mutableListOf() }.add(obj)
            }

            val typeStats =
                byType.mapValues { (_, objs) ->
                    FileTypeStats(
                        count = objs.size,
                        totalSize = objs.sumOf { it.size },
                    )
                }

            StorageStats(
                totalSize = files.sumOf { it.size },
                fileCount = files.size,
                folderCount = folders.size,
                byFileType = typeStats,
            )
        }

    /**
     * Recursively download [folderKey] from [bucketName] into Downloads/<folderName>/,
     * preserving the relative subfolder structure. Each file is downloaded to a temp
     * file first, then saved via [DownloadsSaver]. [onFileProgress] gets
     * (files done, total files, bytes done, total bytes) before each file.
     */
    suspend fun downloadFolder(
        context: Context,
        bucketName: String,
        folderKey: String,
        folderName: String,
        onFileProgress: (filesDone: Int, totalFiles: Int, bytesDone: Long, totalBytes: Long) -> Unit,
    ): Result<FolderDownloadOutcome> =
        s3.listObjectsRecursive(bucketName, folderKey).map { objects ->
            val files = objects.filter { !it.key.endsWith("/") }
            val totalFiles = files.size
            val totalSize = files.sumOf { it.size }
            var downloadedFiles = 0
            var downloadedBytes = 0L

            val cacheDir = context.cacheDir

            files.forEach { obj ->
                // Calculate relative path from folder root
                val relativePath = obj.key.removePrefix(folderKey)
                val relativeSubDir =
                    relativePath.substringBeforeLast('/', "").let { rel ->
                        if (rel.isEmpty()) folderName else "$folderName/$rel"
                    }

                onFileProgress(downloadedFiles, totalFiles, downloadedBytes, totalSize)

                // Download to temp file, then save into Downloads/<folder>/<subdirs>
                val tempFile = File.createTempFile("folder_download_", ".tmp", cacheDir)
                s3
                    .downloadObjectToFile(
                        bucketName = bucketName,
                        key = obj.key,
                        destFile = tempFile,
                        expectedSize = obj.size,
                    ).onSuccess {
                        try {
                            DownloadsSaver.saveToDownloads(
                                context,
                                tempFile,
                                relativePath.substringAfterLast('/'),
                                obj.mimeType,
                                relativeSubDir,
                            )
                            downloadedFiles++
                            downloadedBytes += obj.size
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save ${obj.key}: ${e.message}")
                        }
                        tempFile.delete()
                    }.onFailure { e ->
                        // Log error but continue with other files
                        tempFile.delete()
                        Log.e(TAG, "Failed to download ${obj.key}: ${e.message}")
                    }
            }

            FolderDownloadOutcome(
                totalFiles = totalFiles,
                downloadedFiles = downloadedFiles,
                totalBytes = totalSize,
                downloadedBytes = downloadedBytes,
            )
        }
}
