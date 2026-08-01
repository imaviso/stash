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
import com.imaviso.stash.data.model.ObjectKey
import com.imaviso.stash.data.model.S3Bucket
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.S3Object
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class S3Service private constructor(
    private var currentConfig: S3Config? = null,
) : S3Operations {
    companion object {
        private const val TAG = "S3Service"

        @Volatile
        private var instance: S3Service? = null

        /**
         * Process-wide singleton so account switches propagate to all screens
         * and only one underlying S3Client exists.
         */
        fun getInstance(): S3Service =
            instance ?: synchronized(this) {
                instance ?: S3Service().also { instance = it }
            }

        /**
         * Account-bound instance with its own private client, never swapped.
         * Workers bind the account read at execution start through this so
         * queued work can't race the shared singleton's current account.
         */
        fun forAccount(config: S3Config): S3Operations = S3Service(config)
    }

    private var client: S3Client? = currentConfig?.let(::buildClient)

    /**
     * The single S3Client construction site: endpoint, region, credentials
     * and path-style all resolve here.
     */
    private fun buildClient(config: S3Config): S3Client =
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

    suspend fun initialize(config: S3Config) =
        withContext(Dispatchers.IO) {
            if (currentConfig == config && client != null) return@withContext

            client?.close()

            Log.d(TAG, "Initializing S3 client with endpoint: ${config.endpoint}, region: ${config.region}")

            client = buildClient(config)
            currentConfig = config

            Log.d(TAG, "S3 client initialized successfully")
        }

    private fun requireClient(): S3Client =
        client ?: throw IllegalStateException("S3 client not initialized. Please configure credentials first.")

    /**
     * Test connection with given credentials without storing them.
     * Returns success if we can list buckets, or an error message if not.
     */
    suspend fun testConnection(config: S3Config): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Testing connection to: ${config.endpoint}")

                // Create temporary client for testing (not stored on this instance)
                val testClient = buildClient(config)

                try {
                    val response = testClient.listBuckets(ListBucketsRequest {})
                    val bucketCount = response.buckets?.size ?: 0
                    Log.d(TAG, "Connection test successful: found $bucketCount buckets")
                    "Connected successfully! Found $bucketCount bucket${if (bucketCount != 1) "s" else ""}."
                } finally {
                    testClient.close()
                }
            }.onFailure { e ->
                Log.e(TAG, "Connection test failed: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

    // ==================== BUCKET OPERATIONS ====================

    override suspend fun listBuckets(): Result<List<S3Bucket>> =
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

    override suspend fun createBucket(name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Creating bucket: $name")
                val region = currentConfig?.region ?: "us-east-1"
                requireClient().createBucket(
                    CreateBucketRequest {
                        bucket = name
                        // AWS rejects buckets without a location constraint outside us-east-1
                        if (region != "us-east-1") {
                            createBucketConfiguration =
                                CreateBucketConfiguration {
                                    locationConstraint = BucketLocationConstraint.fromValue(region)
                                }
                        }
                    },
                )
                Log.d(TAG, "Bucket created: $name")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to create bucket: ${e.message}", e)
            }
        }

    override suspend fun deleteBucket(name: String): Result<Unit> =
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

    /**
     * List objects in a bucket with automatic pagination.
     * Fetches all objects by following continuation tokens.
     */
    override suspend fun listObjects(
        bucketName: String,
        prefix: String,
        delimiter: String,
    ): Result<List<S3Object>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Listing objects in bucket: $bucketName, prefix: $prefix")

                val allObjects = mutableListOf<S3Object>()
                val folderKeys = mutableSetOf<String>()
                var continuationToken: String? = null
                var pageCount = 0

                do {
                    pageCount++
                    Log.d(TAG, "Fetching page $pageCount, continuationToken: ${continuationToken?.take(20)}...")

                    val response =
                        requireClient().listObjectsV2(
                            ListObjectsV2Request {
                                bucket = bucketName
                                this.prefix = prefix
                                this.delimiter = delimiter
                                this.continuationToken = continuationToken
                                maxKeys = 1000
                            },
                        )

                    // Add folders (common prefixes)
                    response.commonPrefixes?.forEach { commonPrefix ->
                        commonPrefix.prefix?.let { prefixKey ->
                            if (!folderKeys.contains(prefixKey)) {
                                folderKeys.add(prefixKey)
                                allObjects.add(
                                    S3Object(
                                        key = prefixKey,
                                        size = 0,
                                        lastModified = null,
                                    ),
                                )
                            }
                        }
                    }

                    // Add files (filter out folder markers and current prefix marker)
                    response.contents?.forEach { obj ->
                        val key = obj.key ?: ""
                        val isCurrentPrefix = key == prefix
                        val isFolderMarker = ObjectKey(key).isFolder && (obj.size ?: 0) == 0L
                        val isAlreadyInFolders = folderKeys.contains(key)

                        if (key.isNotEmpty() && !isCurrentPrefix && !isAlreadyInFolders && !isFolderMarker) {
                            allObjects.add(
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

                    continuationToken =
                        if (response.isTruncated == true) {
                            response.nextContinuationToken
                        } else {
                            null
                        }
                } while (continuationToken != null)

                Log.d(TAG, "Found ${allObjects.size} objects across $pageCount page(s)")
                allObjects.toList()
            }.onFailure { e ->
                Log.e(TAG, "Failed to list objects: ${e.message}", e)
            }
        }

    override suspend fun uploadObject(
        bucketName: String,
        key: String,
        data: ByteArray,
        contentType: String,
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
     * Upload a file already on disk (worker temp files) - no extra copy.
     */
    override suspend fun uploadObjectFromFile(
        bucketName: String,
        key: String,
        file: java.io.File,
        contentType: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Uploading file: $key to bucket: $bucketName (${file.length()} bytes)")
                requireClient().putObject(
                    PutObjectRequest {
                        bucket = bucketName
                        this.key = key
                        this.contentType = contentType
                        body = file.asByteStream()
                    },
                )
                Log.d(TAG, "File uploaded: $key")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to upload file: ${e.message}", e)
            }
        }

    /**
     * Upload a file from InputStream - supports large files without loading into memory
     * Uses a temp file approach since AWS SDK streams from files efficiently
     */
    override suspend fun uploadObjectFromStream(
        bucketName: String,
        key: String,
        inputStream: java.io.InputStream,
        contentLength: Long,
        contentType: String,
        cacheDir: java.io.File,
        onProgress: (suspend (Long, Long, String) -> Unit)?,
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

    override suspend fun downloadObject(
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
     * The single presign path: manual SigV4 for path-style endpoints (the AWS
     * SDK presigner mishandles them), SDK presigner for virtual-hosted style.
     */
    private suspend fun presignGetObjectUrl(
        bucketName: String,
        key: String,
        expiresIn: Duration,
    ): String {
        val config = currentConfig ?: throw IllegalStateException("S3 not configured")
        return if (config.usePathStyle) {
            Presigner.generatePresignedUrl(config, bucketName, key, expiresIn)
        } else {
            val request =
                GetObjectRequest {
                    bucket = bucketName
                    this.key = key
                }
            requireClient().presignGetObject(request, expiresIn).url.toString()
        }
    }

    override suspend fun downloadObjectToFile(
        bucketName: String,
        key: String,
        destFile: java.io.File,
        expectedSize: Long,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Downloading object to file: $key from bucket: $bucketName")

                // Presigned URL + plain HTTP: the SDK's getObject can't report progress
                val presignedUrl = presignGetObjectUrl(bucketName, key, 1.hours)

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
    override suspend fun createFolder(
        bucketName: String,
        folderPath: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = ObjectKey(folderPath).asFolder().key
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

    override suspend fun deleteObject(
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

    override suspend fun deleteObjects(
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

    override suspend fun getPresignedUrl(
        bucketName: String,
        key: String,
        expiresIn: Duration,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Generating presigned URL for: $key in bucket: $bucketName")
                val url = presignGetObjectUrl(bucketName, key, expiresIn)
                // Never log the URL itself - it carries credentials + signature
                Log.d(TAG, "Presigned URL generated (path-style: ${currentConfig?.usePathStyle})")
                url
            }.onFailure { e ->
                Log.e(TAG, "Failed to generate presigned URL: ${e.message}", e)
            }
        }

    /**
     * List all objects recursively under a prefix (for folder operations).
     * Returns flat list of all objects including nested ones.
     */
    override suspend fun listObjectsRecursive(
        bucketName: String,
        prefix: String,
    ): Result<List<S3Object>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Listing objects recursively in bucket: $bucketName, prefix: $prefix")

                val allObjects = mutableListOf<S3Object>()
                var continuationToken: String? = null
                var pageCount = 0

                do {
                    pageCount++
                    Log.d(TAG, "Fetching recursive page $pageCount, continuationToken: ${continuationToken?.take(20)}...")

                    val response =
                        requireClient().listObjectsV2(
                            ListObjectsV2Request {
                                bucket = bucketName
                                this.prefix = prefix
                                // No delimiter - get all nested objects
                                this.continuationToken = continuationToken
                                maxKeys = 1000
                            },
                        )

                    // Add all objects (no folder filtering)
                    response.contents?.forEach { obj ->
                        val key = obj.key ?: ""
                        if (key.isNotEmpty() && key != prefix) {
                            allObjects.add(
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

                    continuationToken =
                        if (response.isTruncated == true) {
                            response.nextContinuationToken
                        } else {
                            null
                        }
                } while (continuationToken != null)

                Log.d(TAG, "Found ${allObjects.size} objects recursively across $pageCount page(s)")
                allObjects.toList()
            }.onFailure { e ->
                Log.e(TAG, "Failed to list objects recursively: ${e.message}", e)
            }
        }

    /**
     * URL-encode a CopyObject copySource ("bucket/key") so keys with spaces,
     * '+' or unicode don't break the request. Segment encoding rules are
     * owned by [ObjectKey.encoded].
     */
    private fun encodeCopySource(
        bucket: String,
        key: String,
    ): String = "$bucket/${ObjectKey(key).encoded}"

    override suspend fun copyObject(
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
                        copySource = encodeCopySource(sourceBucket, sourceKey)
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

    override suspend fun objectExists(
        bucketName: String,
        key: String,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                try {
                    requireClient().headObject(
                        HeadObjectRequest {
                            bucket = bucketName
                            this.key = key
                        },
                    )
                    true
                } catch (e: NoSuchKey) {
                    false
                } catch (e: NotFound) {
                    false
                }
            }
        }

    override fun close() {
        Log.d(TAG, "Closing S3 client")
        client?.close()
        client = null
        currentConfig = null
    }
}
