package com.imaviso.stash.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WorkManager worker for background file uploads
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "UploadWorker"

        // Input data keys
        const val KEY_BUCKET_NAME = "bucket_name"
        const val KEY_OBJECT_KEY = "object_key"
        const val KEY_FILE_URI = "file_uri"
        const val KEY_CONTENT_TYPE = "content_type"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TRANSFER_ID = "transfer_id"

        // Output/Progress data keys
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_UPLOADED = "bytes_uploaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"

        // Status values
        const val STATUS_PREPARING = "preparing"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"

        fun createInputData(
            bucketName: String,
            objectKey: String,
            fileUri: String,
            contentType: String,
            fileName: String,
            transferId: String =
                java.util.UUID
                    .randomUUID()
                    .toString(),
        ): Data =
            workDataOf(
                KEY_BUCKET_NAME to bucketName,
                KEY_OBJECT_KEY to objectKey,
                KEY_FILE_URI to fileUri,
                KEY_CONTENT_TYPE to contentType,
                KEY_FILE_NAME to fileName,
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
            val fileUriString =
                inputData.getString(KEY_FILE_URI) ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Missing file URI"),
                )
            val contentType = inputData.getString(KEY_CONTENT_TYPE) ?: "application/octet-stream"
            val fileName = inputData.getString(KEY_FILE_NAME) ?: objectKey.substringAfterLast('/')
            val transferId =
                inputData.getString(KEY_TRANSFER_ID) ?: java.util.UUID
                    .randomUUID()
                    .toString()

            Log.d(TAG, "Starting upload: $fileName to $bucketName/$objectKey (transferId: $transferId)")

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

                // Copy URI content to temp file
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

                // Copy URI content to a temp file; total size is unknown until
                // the copy completes, so this phase reports bytes only.
                val fileUri = Uri.parse(fileUriString)
                val tempFile = File.createTempFile("upload_", ".tmp", applicationContext.cacheDir)

                var totalBytes = 0L
                var lastProgressBytes = 0L
                applicationContext.contentResolver.openInputStream(fileUri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            // Publish ~every 1MB so the transfer list shows prep movement
                            if (totalBytes - lastProgressBytes >= 1024 * 1024) {
                                lastProgressBytes = totalBytes
                                setProgress(
                                    workDataOf(
                                        KEY_STATUS to STATUS_PREPARING,
                                        KEY_PROGRESS to 0,
                                        KEY_BYTES_UPLOADED to totalBytes,
                                    ),
                                )
                            }
                        }
                    }
                } ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Cannot read file"),
                )

                Log.d(TAG, "Temp file created: ${tempFile.length()} bytes")

                setProgress(
                    workDataOf(
                        KEY_STATUS to STATUS_UPLOADING,
                        KEY_TOTAL_BYTES to totalBytes,
                        KEY_BYTES_UPLOADED to totalBytes,
                    ),
                )
                setForeground(createForegroundInfo(fileName, 0, transferId))

                // Bind to the account read at execution start. The shared
                // singleton may have been re-initialized to another account
                // by a screen since this work was enqueued.
                val s3 = S3Service.forAccount(config)

                try {
                    s3
                        .uploadObjectFromFile(
                            bucketName = bucketName,
                            key = objectKey,
                            file = tempFile,
                            contentType = contentType,
                        ).getOrThrow()

                    Log.d(TAG, "Upload complete: $fileName")

                    // Show completion notification
                    notificationManager.showUploadComplete(fileName, true, transferId)

                    Result.success(
                        workDataOf(
                            KEY_STATUS to STATUS_COMPLETE,
                            KEY_PROGRESS to 100,
                            KEY_BYTES_UPLOADED to totalBytes,
                            KEY_TOTAL_BYTES to totalBytes,
                        ),
                    )
                } finally {
                    s3.close()
                    tempFile.delete()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Upload failed: ${e.message}", e)
                notificationManager.showUploadComplete(fileName, false, transferId)
                Result.failure(
                    workDataOf(
                        KEY_STATUS to STATUS_FAILED,
                        KEY_ERROR to (e.message ?: "Upload failed"),
                    ),
                )
            }
        }

    private fun createForegroundInfo(
        fileName: String,
        progress: Int,
        transferId: String,
    ): ForegroundInfo {
        val notification =
            notificationManager
                .createUploadNotification(
                    fileName = fileName,
                    progress = progress,
                    isIndeterminate = progress == 0,
                ).build()

        val notificationId = TransferNotificationManager.getNotificationId(transferId, true)

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
