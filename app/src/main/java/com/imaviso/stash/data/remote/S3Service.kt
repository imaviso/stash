package com.imaviso.stash.data.remote

import android.util.Log
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
import com.imaviso.stash.data.model.S3Bucket
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.S3Object
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import kotlin.time.Duration.Companion.hours

class S3Service {
    companion object {
        private const val TAG = "S3Service"
    }

    private var client: S3Client? = null
    private var currentConfig: S3Config? = null

    suspend fun initialize(config: S3Config) =
        withContext(Dispatchers.IO) {
            if (currentConfig == config && client != null) return@withContext

            client?.close()

            Log.d(TAG, "Initializing S3 client with endpoint: ${config.endpoint}, region: ${config.region}")

            // Test basic connectivity using HttpURLConnection
            try {
                Log.d(TAG, "Testing HTTP connectivity to ${config.endpoint}")
                val url = URL(config.endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                val responseCode = connection.responseCode
                Log.d(TAG, "HTTP connectivity test response code: $responseCode")
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "HTTP connectivity test failed: ${e.javaClass.simpleName}: ${e.message}", e)
            }

            client =
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
            currentConfig = config

            Log.d(TAG, "S3 client initialized successfully")
        }

    private fun requireClient(): S3Client =
        client ?: throw IllegalStateException("S3 client not initialized. Please configure credentials first.")

    // ==================== BUCKET OPERATIONS ====================

    suspend fun listBuckets(): Result<List<S3Bucket>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Listing buckets...")
                val response = requireClient().listBuckets(ListBucketsRequest {})
                val buckets =
                    response.buckets?.map { bucket ->
                        S3Bucket(
                            name = bucket.name ?: "",
                            creationDate = bucket.creationDate?.let { Date(it.epochSeconds * 1000) },
                        )
                    } ?: emptyList()
                Log.d(TAG, "Found ${buckets.size} buckets")
                buckets
            }.onFailure { e ->
                Log.e(TAG, "Failed to list buckets: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

    suspend fun createBucket(name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Creating bucket: $name")
                requireClient().createBucket(
                    CreateBucketRequest {
                        bucket = name
                    },
                )
                Log.d(TAG, "Bucket created: $name")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to create bucket: ${e.message}", e)
            }
        }

    suspend fun deleteBucket(name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Deleting bucket: $name")
                requireClient().deleteBucket(
                    DeleteBucketRequest {
                        bucket = name
                    },
                )
                Log.d(TAG, "Bucket deleted: $name")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to delete bucket: ${e.message}", e)
            }
        }

    // ==================== OBJECT OPERATIONS ====================

    suspend fun listObjects(
        bucketName: String,
        prefix: String = "",
        delimiter: String = "/",
    ): Result<List<S3Object>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Listing objects in bucket: $bucketName, prefix: $prefix")
                val response =
                    requireClient().listObjectsV2(
                        ListObjectsV2Request {
                            bucket = bucketName
                            this.prefix = prefix
                            this.delimiter = delimiter
                        },
                    )

                val objects = mutableListOf<S3Object>()

                // Collect folder keys from common prefixes to avoid duplicates
                val folderKeys = mutableSetOf<String>()

                // Add folders (common prefixes)
                response.commonPrefixes?.forEach { commonPrefix ->
                    commonPrefix.prefix?.let { prefixKey ->
                        folderKeys.add(prefixKey)
                        objects.add(
                            S3Object(
                                key = prefixKey,
                                size = 0,
                                lastModified = null,
                            ),
                        )
                    }
                }

                // Add files (filter out folder markers and current prefix marker)
                response.contents?.forEach { obj ->
                    val key = obj.key ?: ""
                    // Skip:
                    // 1. Empty keys
                    // 2. The current prefix itself (folder marker for current directory)
                    // 3. Folder marker objects that we already have from commonPrefixes
                    // 4. Zero-byte objects ending with "/" (folder markers)
                    val isCurrentPrefix = key == prefix
                    val isFolderMarker = key.endsWith("/") && (obj.size ?: 0) == 0L
                    val isAlreadyInFolders = folderKeys.contains(key)

                    if (key.isNotEmpty() && !isCurrentPrefix && !isAlreadyInFolders && !isFolderMarker) {
                        objects.add(
                            S3Object(
                                key = key,
                                size = obj.size ?: 0,
                                lastModified = obj.lastModified?.let { Date(it.epochSeconds * 1000) },
                                etag = obj.eTag,
                                storageClass = obj.storageClass?.value,
                            ),
                        )
                    }
                }

                Log.d(TAG, "Found ${objects.size} objects")
                objects.toList()
            }.onFailure { e ->
                Log.e(TAG, "Failed to list objects: ${e.message}", e)
            }
        }

    suspend fun uploadObject(
        bucketName: String,
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Uploading object: $key to bucket: $bucketName")
                requireClient().putObject(
                    PutObjectRequest {
                        bucket = bucketName
                        this.key = key
                        this.contentType = contentType
                        body = ByteStream.fromBytes(data)
                    },
                )
                Log.d(TAG, "Object uploaded: $key")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to upload object: ${e.message}", e)
            }
        }

    /**
     * Upload a file from InputStream - supports large files without loading into memory
     * Uses a temp file approach since AWS SDK streams from files efficiently
     * @param onProgress callback with (bytesWritten, totalBytes, phase) - phase is "preparing" or "uploading"
     */
    suspend fun uploadObjectFromStream(
        bucketName: String,
        key: String,
        inputStream: java.io.InputStream,
        contentLength: Long,
        contentType: String = "application/octet-stream",
        cacheDir: java.io.File,
        onProgress: ((Long, Long, String) -> Unit)? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            var tempFile: java.io.File? = null
            runCatching {
                Log.d(TAG, "Uploading object (streaming): $key to bucket: $bucketName, size: $contentLength bytes")

                // Phase 1: Write InputStream to temp file
                tempFile = java.io.File.createTempFile("upload_", ".tmp", cacheDir)
                tempFile!!.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalWritten = 0L
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalWritten += bytesRead
                        // Report progress every 100KB
                        if (totalWritten % (100 * 1024) < 8192) {
                            onProgress?.invoke(totalWritten, contentLength, "preparing")
                        }
                    }
                }
                inputStream.close()

                Log.d(TAG, "Temp file created, starting S3 upload...")
                onProgress?.invoke(contentLength, contentLength, "uploading")

                // Phase 2: Upload to S3
                // Note: AWS SDK doesn't provide upload progress for putObject directly
                // The actual network transfer happens here
                requireClient().putObject(
                    PutObjectRequest {
                        bucket = bucketName
                        this.key = key
                        this.contentType = contentType
                        body = tempFile!!.asByteStream()
                    },
                )

                onProgress?.invoke(contentLength, contentLength, "complete")
                Log.d(TAG, "Object uploaded (streaming): $key")
                Unit
            }.also {
                // Clean up temp file
                tempFile?.delete()
            }.onFailure { e ->
                Log.e(TAG, "Failed to upload object (streaming): ${e.message}", e)
            }
        }

    suspend fun downloadObject(
        bucketName: String,
        key: String,
    ): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Downloading object: $key from bucket: $bucketName")
                val response =
                    requireClient().getObject(
                        GetObjectRequest {
                            bucket = bucketName
                            this.key = key
                        },
                    ) { response ->
                        response.body?.toByteArray() ?: ByteArray(0)
                    }
                Log.d(TAG, "Object downloaded: $key")
                response
            }.onFailure { e ->
                Log.e(TAG, "Failed to download object: ${e.message}", e)
            }
        }

    /**
     * Download a file with progress reporting - streams to a file to avoid OOM
     * @param onProgress callback with (bytesWritten, totalBytes)
     */
    suspend fun downloadObjectToFile(
        bucketName: String,
        key: String,
        destFile: java.io.File,
        expectedSize: Long,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Downloading object to file: $key from bucket: $bucketName")

                // Get presigned URL and download via HttpURLConnection for progress
                val config = currentConfig ?: throw IllegalStateException("S3 not configured")
                val presignedUrl =
                    if (config.usePathStyle) {
                        generateManualPresignedUrl(config, bucketName, key, 1.hours)
                    } else {
                        val request =
                            GetObjectRequest {
                                bucket = bucketName
                                this.key = key
                            }
                        requireClient().presignGetObject(request, 1.hours).url.toString()
                    }

                val url = URL(presignedUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.connect()

                val totalBytes = if (expectedSize > 0) expectedSize else connection.contentLengthLong

                connection.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalWritten = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalWritten += bytesRead
                            // Report progress every ~100KB
                            if (totalWritten % (100 * 1024) < 8192) {
                                onProgress?.invoke(totalWritten, totalBytes)
                            }
                        }
                        onProgress?.invoke(totalWritten, totalBytes)
                    }
                }

                connection.disconnect()
                Log.d(TAG, "Object downloaded to file: $key")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to download object to file: ${e.message}", e)
                destFile.delete()
            }
        }

    /**
     * Create a folder (empty object with trailing slash)
     */
    suspend fun createFolder(
        bucketName: String,
        folderPath: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
                Log.d(TAG, "Creating folder: $key in bucket: $bucketName")
                requireClient().putObject(
                    PutObjectRequest {
                        bucket = bucketName
                        this.key = key
                        body = ByteStream.fromBytes(ByteArray(0))
                    },
                )
                Log.d(TAG, "Folder created: $key")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to create folder: ${e.message}", e)
            }
        }

    /**
     * Rename/move an object (copy + delete)
     */
    suspend fun renameObject(
        bucketName: String,
        oldKey: String,
        newKey: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Renaming object from $oldKey to $newKey in bucket: $bucketName")

                // Copy to new key
                requireClient().copyObject(
                    CopyObjectRequest {
                        copySource = "$bucketName/$oldKey"
                        bucket = bucketName
                        key = newKey
                    },
                )

                // Delete old key
                requireClient().deleteObject(
                    DeleteObjectRequest {
                        bucket = bucketName
                        key = oldKey
                    },
                )

                Log.d(TAG, "Object renamed from $oldKey to $newKey")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to rename object: ${e.message}", e)
            }
        }

    suspend fun deleteObject(
        bucketName: String,
        key: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Deleting object: $key from bucket: $bucketName")
                requireClient().deleteObject(
                    DeleteObjectRequest {
                        bucket = bucketName
                        this.key = key
                    },
                )
                Log.d(TAG, "Object deleted: $key")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to delete object: ${e.message}", e)
            }
        }

    suspend fun deleteObjects(
        bucketName: String,
        keys: List<String>,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Deleting ${keys.size} objects from bucket: $bucketName")
                requireClient().deleteObjects(
                    DeleteObjectsRequest {
                        bucket = bucketName
                        delete =
                            Delete {
                                objects =
                                    keys.map { keyName ->
                                        ObjectIdentifier { key = keyName }
                                    }
                            }
                    },
                )
                Log.d(TAG, "Objects deleted")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to delete objects: ${e.message}", e)
            }
        }

    /**
     * Generate a presigned URL for streaming large files (video/audio)
     * This allows ExoPlayer to stream directly from S3 without loading into memory
     *
     * For Garage with path-style access, we manually sign the URL since the AWS SDK
     * presigner doesn't correctly handle path-style URLs.
     */
    suspend fun getPresignedUrl(
        bucketName: String,
        key: String,
        expiresIn: kotlin.time.Duration = 1.hours,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Generating presigned URL for: $key in bucket: $bucketName")

                val config = currentConfig ?: throw IllegalStateException("S3 not configured")

                // For path-style access (Garage), we need to manually construct and sign the URL
                // because AWS SDK presigner doesn't handle path-style correctly
                if (config.usePathStyle) {
                    val url = generateManualPresignedUrl(config, bucketName, key, expiresIn)
                    Log.d(TAG, "Manually signed presigned URL generated: $url")
                    return@runCatching url
                }

                // For virtual-hosted style (standard AWS), use SDK presigner
                val request =
                    GetObjectRequest {
                        bucket = bucketName
                        this.key = key
                    }
                val presignedRequest = requireClient().presignGetObject(request, expiresIn)
                val url = presignedRequest.url.toString()
                Log.d(TAG, "SDK presigned URL generated: $url")
                url
            }.onFailure { e ->
                Log.e(TAG, "Failed to generate presigned URL: ${e.message}", e)
            }
        }

    /**
     * Manually generate a presigned URL for path-style S3 access (Garage, MinIO, etc.)
     * Uses AWS Signature Version 4
     */
    private fun generateManualPresignedUrl(
        config: S3Config,
        bucketName: String,
        key: String,
        expiresIn: kotlin.time.Duration,
    ): String {
        val endpoint = config.endpoint.trimEnd('/')
        val region = config.region
        val accessKey = config.accessKey
        val secretKey = config.secretKey

        // Use SimpleDateFormat for API 24 compatibility
        val now = java.util.Date()
        val dateTimeFormat =
            java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        val dateFormat =
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

        val amzDate = dateTimeFormat.format(now)
        val dateStamp = dateFormat.format(now)
        val expiresSeconds = expiresIn.inWholeSeconds

        // Parse endpoint to get host
        val endpointUrl = java.net.URI(endpoint)
        val host =
            if (endpointUrl.port != -1 && endpointUrl.port != 80 && endpointUrl.port != 443) {
                "${endpointUrl.host}:${endpointUrl.port}"
            } else {
                endpointUrl.host
            }

        // URL encode the key (but not the slashes for path)
        val encodedKey =
            java.net.URLEncoder
                .encode(key, "UTF-8")
                .replace("%2F", "/")
                .replace("+", "%20")

        val canonicalUri = "/$bucketName/$encodedKey"
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val credential = java.net.URLEncoder.encode("$accessKey/$credentialScope", "UTF-8")

        // Build canonical query string (sorted alphabetically)
        val queryParams =
            sortedMapOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to credential,
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresSeconds.toString(),
                "X-Amz-SignedHeaders" to "host",
            )

        val canonicalQueryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }

        // Build canonical request
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

        // Create string to sign
        val canonicalRequestHash = sha256Hex(canonicalRequest)
        val stringToSign =
            listOf(
                "AWS4-HMAC-SHA256",
                amzDate,
                credentialScope,
                canonicalRequestHash,
            ).joinToString("\n")

        // Calculate signature
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
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(
        key: ByteArray,
        data: String,
    ): String = hmacSha256(key, data).joinToString("") { "%02x".format(it) }

    suspend fun copyObject(
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Copying object from $sourceBucket/$sourceKey to $destBucket/$destKey")
                requireClient().copyObject(
                    CopyObjectRequest {
                        copySource = "$sourceBucket/$sourceKey"
                        bucket = destBucket
                        key = destKey
                    },
                )
                Log.d(TAG, "Object copied")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to copy object: ${e.message}", e)
            }
        }

    fun close() {
        Log.d(TAG, "Closing S3 client")
        client?.close()
        client = null
        currentConfig = null
    }
}
