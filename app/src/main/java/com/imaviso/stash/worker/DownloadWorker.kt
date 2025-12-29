package com.imaviso.stash.worker

import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.net.url.Url
import com.imaviso.stash.data.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.hours

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
        ): Data =
            workDataOf(
                KEY_BUCKET_NAME to bucketName,
                KEY_OBJECT_KEY to objectKey,
                KEY_FILE_NAME to fileName,
                KEY_FILE_SIZE to fileSize,
                KEY_MIME_TYPE to mimeType,
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
            val fileName = inputData.getString(KEY_FILE_NAME) ?: objectKey.substringAfterLast('/')
            val fileSize = inputData.getLong(KEY_FILE_SIZE, 0L)
            val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"

            Log.d(TAG, "Starting download: $fileName from $bucketName/$objectKey")

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

                setProgress(
                    workDataOf(
                        KEY_STATUS to STATUS_PREPARING,
                        KEY_PROGRESS to 0,
                    ),
                )

                // Generate presigned URL
                val presignedUrl =
                    if (config.usePathStyle) {
                        generateManualPresignedUrl(config, bucketName, objectKey)
                    } else {
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
                            val request =
                                GetObjectRequest {
                                    bucket = bucketName
                                    key = objectKey
                                }
                            s3Client.presignGetObject(request, 1.hours).url.toString()
                        } finally {
                            s3Client.close()
                        }
                    }

                Log.d(TAG, "Presigned URL generated, starting download...")

                setProgress(
                    workDataOf(
                        KEY_STATUS to STATUS_DOWNLOADING,
                        KEY_PROGRESS to 5,
                        KEY_TOTAL_BYTES to fileSize,
                    ),
                )
                setForeground(createForegroundInfo(fileName, 5))

                // Download to temp file first
                val tempFile = File.createTempFile("download_", ".tmp", applicationContext.cacheDir)

                val url = URL(presignedUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.connect()

                val totalBytes = if (fileSize > 0) fileSize else connection.contentLengthLong
                var bytesDownloaded = 0L
                var lastProgressUpdate = 0

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            // Update progress every 2%
                            val progress =
                                if (totalBytes > 0) {
                                    ((bytesDownloaded * 100) / totalBytes).toInt()
                                } else {
                                    50 // Indeterminate
                                }

                            if (progress >= lastProgressUpdate + 2) {
                                lastProgressUpdate = progress
                                setProgress(
                                    workDataOf(
                                        KEY_STATUS to STATUS_DOWNLOADING,
                                        KEY_PROGRESS to progress,
                                        KEY_BYTES_DOWNLOADED to bytesDownloaded,
                                        KEY_TOTAL_BYTES to totalBytes,
                                    ),
                                )
                                setForeground(createForegroundInfo(fileName, progress))
                            }
                        }
                    }
                }

                connection.disconnect()

                Log.d(TAG, "Download to temp file complete: ${tempFile.length()} bytes")

                // Save to Downloads using MediaStore (works on all Android versions)
                val outputPath = saveToDownloads(tempFile, fileName, mimeType)
                tempFile.delete()

                Log.d(TAG, "Saved to Downloads: $outputPath")

                // Show completion notification
                notificationManager.showDownloadComplete(fileName, true)

                Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_COMPLETE,
                        KEY_PROGRESS to 100,
                        KEY_BYTES_DOWNLOADED to bytesDownloaded,
                        KEY_TOTAL_BYTES to totalBytes,
                        KEY_OUTPUT_PATH to outputPath,
                    ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                notificationManager.showDownloadComplete(fileName, false)
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
    ): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ use MediaStore
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

            val resolver = applicationContext.contentResolver
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to write to MediaStore")

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            uri.toString()
        } else {
            // Android 9 and below - save directly to Downloads
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, fileName)
            tempFile.copyTo(destFile, overwrite = true)
            destFile.absolutePath
        }

    private fun createForegroundInfo(
        fileName: String,
        progress: Int,
    ): ForegroundInfo {
        val notification =
            notificationManager
                .createDownloadNotification(
                    fileName = fileName,
                    progress = progress,
                    isIndeterminate = progress == 0,
                ).build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                TransferNotificationManager.DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                TransferNotificationManager.DOWNLOAD_NOTIFICATION_ID,
                notification,
            )
        }
    }

    // Manual presigned URL generation for path-style access (Garage, MinIO, etc.)
    private fun generateManualPresignedUrl(
        config: com.imaviso.stash.data.model.S3Config,
        bucketName: String,
        key: String,
    ): String {
        val endpoint = config.endpoint.trimEnd('/')
        val region = config.region
        val accessKey = config.accessKey
        val secretKey = config.secretKey
        val expiresIn = 1.hours

        val now = Date()
        val dateTimeFormat =
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        val dateFormat =
            SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        val amzDate = dateTimeFormat.format(now)
        val dateStamp = dateFormat.format(now)
        val expiresSeconds = expiresIn.inWholeSeconds

        val endpointUrl = java.net.URI(endpoint)
        val host =
            if (endpointUrl.port != -1 && endpointUrl.port != 80 && endpointUrl.port != 443) {
                "${endpointUrl.host}:${endpointUrl.port}"
            } else {
                endpointUrl.host
            }

        val encodedKey =
            URLEncoder
                .encode(key, "UTF-8")
                .replace("%2F", "/")
                .replace("+", "%20")

        val canonicalUri = "/$bucketName/$encodedKey"
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val credential = URLEncoder.encode("$accessKey/$credentialScope", "UTF-8")

        val queryParams =
            sortedMapOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to credential,
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresSeconds.toString(),
                "X-Amz-SignedHeaders" to "host",
            )

        val canonicalQueryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }

        val canonicalHeaders = "host:$host\n"
        val signedHeaders = "host"
        val payloadHash = "UNSIGNED-PAYLOAD"

        val canonicalRequest =
            listOf(
                "GET",
                canonicalUri,
                canonicalQueryString,
                canonicalHeaders,
                signedHeaders,
                payloadHash,
            ).joinToString("\n")

        val canonicalRequestHash = sha256Hex(canonicalRequest)
        val stringToSign =
            listOf(
                "AWS4-HMAC-SHA256",
                amzDate,
                credentialScope,
                canonicalRequestHash,
            ).joinToString("\n")

        val kDate = hmacSha256("AWS4$secretKey".toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, "s3")
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = hmacSha256Hex(kSigning, stringToSign)

        return "$endpoint$canonicalUri?$canonicalQueryString&X-Amz-Signature=$signature"
    }

    private fun sha256Hex(data: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(
        key: ByteArray,
        data: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(
        key: ByteArray,
        data: String,
    ): String = hmacSha256(key, data).joinToString("") { "%02x".format(it) }
}
