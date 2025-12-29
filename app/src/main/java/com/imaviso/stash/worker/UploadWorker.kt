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
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import com.imaviso.stash.data.repository.ConfigRepository
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
        ): Data =
            workDataOf(
                KEY_BUCKET_NAME to bucketName,
                KEY_OBJECT_KEY to objectKey,
                KEY_FILE_URI to fileUri,
                KEY_CONTENT_TYPE to contentType,
                KEY_FILE_NAME to fileName,
            )
    }

    private val notificationManager = TransferNotificationManager(applicationContext)
    private val configRepository = ConfigRepository(applicationContext)

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

            Log.d(TAG, "Starting upload: $fileName to $bucketName/$objectKey")

            try {
                // Get S3 config
                val config = configRepository.configFlow.first()
                if (!config.isValid()) {
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "S3 not configured"),
                    )
                }

                // Set as foreground with notification
                setForeground(createForegroundInfo(fileName, 0))

                // Copy URI content to temp file
                setProgress(
                    workDataOf(
                        KEY_STATUS to STATUS_PREPARING,
                        KEY_PROGRESS to 0,
                    ),
                )

                val fileUri = Uri.parse(fileUriString)
                val tempFile = File.createTempFile("upload_", ".tmp", applicationContext.cacheDir)

                var totalBytes = 0L
                applicationContext.contentResolver.openInputStream(fileUri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                    }
                } ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Cannot read file"),
                )

                Log.d(TAG, "Temp file created: ${tempFile.length()} bytes")

                // Update progress - preparing complete
                setProgress(
                    workDataOf(
                        KEY_STATUS to STATUS_UPLOADING,
                        KEY_PROGRESS to 10,
                        KEY_TOTAL_BYTES to totalBytes,
                    ),
                )
                setForeground(createForegroundInfo(fileName, 10))

                // Create S3 client and upload
                val s3Client =
                    S3Client {
                        region = config.region
                        endpointUrl = Url.parse(config.endpoint)
                        credentialsProvider =
                            StaticCredentialsProvider {
                                accessKeyId = config.accessKey
                                secretAccessKey = config.secretKey
                            }
                        forcePathStyle = config.usePathStyle
                    }

                try {
                    s3Client.putObject(
                        PutObjectRequest {
                            bucket = bucketName
                            key = objectKey
                            this.contentType = contentType
                            body = tempFile.asByteStream()
                        },
                    )

                    Log.d(TAG, "Upload complete: $fileName")

                    // Show completion notification
                    notificationManager.showUploadComplete(fileName, true)

                    Result.success(
                        workDataOf(
                            KEY_STATUS to STATUS_COMPLETE,
                            KEY_PROGRESS to 100,
                            KEY_BYTES_UPLOADED to totalBytes,
                            KEY_TOTAL_BYTES to totalBytes,
                        ),
                    )
                } finally {
                    s3Client.close()
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}", e)
                notificationManager.showUploadComplete(fileName, false)
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
    ): ForegroundInfo {
        val notification =
            notificationManager
                .createUploadNotification(
                    fileName = fileName,
                    progress = progress,
                    isIndeterminate = progress == 0,
                ).build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                TransferNotificationManager.UPLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                TransferNotificationManager.UPLOAD_NOTIFICATION_ID,
                notification,
            )
        }
    }
}
