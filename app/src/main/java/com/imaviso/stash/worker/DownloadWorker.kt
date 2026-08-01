package com.imaviso.stash.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.util.DownloadsSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WorkManager worker for background file downloads
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "DownloadWorker"

        // Input data keys
        const val KEY_BUCKET_NAME = "bucket_name"
        const val KEY_OBJECT_KEY = "object_key"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FILE_SIZE = "file_size"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_TRANSFER_ID = "transfer_id"

        // Output/Progress data keys
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_OUTPUT_PATH = "output_path"

        // Status values
        const val STATUS_PREPARING = "preparing"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"

        fun createInputData(
            bucketName: String,
            objectKey: String,
            fileName: String,
            fileSize: Long,
            mimeType: String,
            transferId: String =
                java.util.UUID
                    .randomUUID()
                    .toString(),
        ): Data =
            workDataOf(
                KEY_BUCKET_NAME to bucketName,
                KEY_OBJECT_KEY to objectKey,
                KEY_FILE_NAME to fileName,
                KEY_FILE_SIZE to fileSize,
                KEY_MIME_TYPE to mimeType,
                KEY_TRANSFER_ID to transferId,
            )
    }

    private val notificationManager = TransferNotificationManager(applicationContext)
    private val configRepository = ConfigRepository.getInstance(applicationContext)

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val bucketName =
                inputData.getString(KEY_BUCKET_NAME) ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Missing bucket name"),
                )
            val objectKey =
                inputData.getString(KEY_OBJECT_KEY) ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Missing object key"),
                )
            val fileName = inputData.getString(KEY_FILE_NAME) ?: objectKey.substringAfterLast('/')
            val fileSize = inputData.getLong(KEY_FILE_SIZE, 0L)
            val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
            val transferId =
                inputData.getString(KEY_TRANSFER_ID) ?: java.util.UUID
                    .randomUUID()
                    .toString()

            Log.d(TAG, "Starting download: $fileName from $bucketName/$objectKey (transferId: $transferId)")

            try {
                // Get S3 config
                val config = configRepository.configFlow.first()
                if (!config.isValid()) {
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "S3 not configured"),
                    )
                }

                // Set as foreground with notification
                setForeground(createForegroundInfo(fileName, 0, transferId))

                // (fileName/bucket ride along so the transfer module's collector
                // can build complete records for works surviving process death)
                setProgress(
                    workDataOf(
                        KEY_STATUS to STATUS_PREPARING,
                        KEY_PROGRESS to 0,
                        KEY_FILE_NAME to fileName,
                        KEY_BUCKET_NAME to bucketName,
                    ),
                )

                // Bind to the account read at execution start. The shared
                // singleton may have been re-initialized to another account
                // by a screen since this work was enqueued.
                val s3 = S3Service.forAccount(config)

                try {
                    // Download to temp file via the port (presigned URL + progress)
                    val tempFile = File.createTempFile("download_", ".tmp", applicationContext.cacheDir)

                    setProgress(
                        workDataOf(
                            KEY_STATUS to STATUS_DOWNLOADING,
                            KEY_PROGRESS to 0,
                            KEY_TOTAL_BYTES to fileSize,
                        ),
                    )

                    var bytesDownloaded = 0L
                    var totalBytes = fileSize
                    var lastProgressUpdate = 0

                    s3
                        .downloadObjectToFile(
                            bucketName = bucketName,
                            key = objectKey,
                            destFile = tempFile,
                            expectedSize = fileSize,
                            onProgress = { written, total ->
                                bytesDownloaded = written
                                totalBytes = total
                                val progress =
                                    if (total > 0) {
                                        ((written * 100) / total).toInt()
                                    } else {
                                        50 // Indeterminate
                                    }

                                // Publish at most every 2% to limit WorkManager/notification churn
                                if (progress >= lastProgressUpdate + 2 || progress == 100) {
                                    lastProgressUpdate = progress
                                    setProgress(
                                        workDataOf(
                                            KEY_STATUS to STATUS_DOWNLOADING,
                                            KEY_PROGRESS to progress,
                                            KEY_BYTES_DOWNLOADED to bytesDownloaded,
                                            KEY_TOTAL_BYTES to totalBytes,
                                        ),
                                    )
                                    setForeground(createForegroundInfo(fileName, progress, transferId))
                                }
                            },
                        ).getOrThrow()

                    Log.d(TAG, "Download to temp file complete: ${tempFile.length()} bytes")

                    // Save to Downloads using MediaStore (works on all Android versions)
                    val outputPath = saveToDownloads(tempFile, fileName, mimeType)
                    tempFile.delete()

                    Log.d(TAG, "Saved to Downloads: $outputPath")

                    // Show completion notification
                    notificationManager.showDownloadComplete(fileName, true, transferId)

                    Result.success(
                        workDataOf(
                            KEY_STATUS to STATUS_COMPLETE,
                            KEY_PROGRESS to 100,
                            KEY_BYTES_DOWNLOADED to bytesDownloaded,
                            KEY_TOTAL_BYTES to totalBytes,
                            KEY_OUTPUT_PATH to outputPath,
                        ),
                    )
                } finally {
                    s3.close()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Download failed: ${e.message}", e)
                notificationManager.showDownloadComplete(fileName, false, transferId)
                Result.failure(
                    workDataOf(
                        KEY_STATUS to STATUS_FAILED,
                        KEY_ERROR to (e.message ?: "Download failed"),
                    ),
                )
            }
        }

    private fun saveToDownloads(
        tempFile: File,
        fileName: String,
        mimeType: String,
    ): String = DownloadsSaver.saveToDownloads(applicationContext, tempFile, fileName, mimeType)

    private fun createForegroundInfo(
        fileName: String,
        progress: Int,
        transferId: String,
    ): ForegroundInfo {
        val notification =
            notificationManager
                .createDownloadNotification(
                    fileName = fileName,
                    progress = progress,
                    isIndeterminate = progress == 0,
                ).build()

        val notificationId = TransferNotificationManager.getNotificationId(transferId, false)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                notificationId,
                notification,
            )
        }
    }

}
