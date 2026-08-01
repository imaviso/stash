package com.imaviso.stash.data.remote

import com.imaviso.stash.data.model.S3Config
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

/**
 * Manual AWS Signature Version 4 presigner for path-style S3 access (Garage, MinIO, etc.).
 * The AWS SDK presigner doesn't correctly handle path-style URLs, so this is used
 * whenever `usePathStyle` is enabled. Used by S3Service (the port's AWS adapter).
 */
object Presigner {
    fun generatePresignedUrl(
        config: S3Config,
        bucketName: String,
        key: String,
        expiresIn: Duration,
    ): String {
        val endpoint = config.endpoint.trimEnd('/')
        val region = config.region
        val accessKey = config.accessKey
        val secretKey = config.secretKey

        // Use SimpleDateFormat for API 24 compatibility
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
            URLEncoder
                .encode(key, "UTF-8")
                .replace("%2F", "/")
                .replace("+", "%20")

        val canonicalUri = "/$bucketName/$encodedKey"
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val credential = URLEncoder.encode("$accessKey/$credentialScope", "UTF-8")

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
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(
        key: ByteArray,
        data: String,
    ): String = hmacSha256(key, data).joinToString("") { "%02x".format(it) }
}
