package com.imaviso.stash.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact user-facing strings of ErrorUtils' message-keyword matcher.
 * Matching is keyword-based on the lowercased message, tested as implemented.
 */
class ErrorUtilsTest {
    // --- Network errors ---

    @Test
    fun `unable to resolve host maps to cannot reach server`() {
        val e = UnknownHostException("Unable to resolve host \"s3.example.com\": No address associated")
        assertEquals(
            "Cannot reach server. Check the endpoint URL and your internet connection.",
            ErrorUtils.formatError(e),
        )
    }

    @Test
    fun `UnknownHostException class name in message maps to cannot reach server`() {
        assertEquals(
            "Cannot reach server. Check the endpoint URL and your internet connection.",
            ErrorUtils.formatErrorMessage("java.net.UnknownHostException: example.com"),
        )
    }

    @Test
    fun `no address associated maps to cannot reach server`() {
        assertEquals(
            "Cannot reach server. Check the endpoint URL and your internet connection.",
            ErrorUtils.formatErrorMessage("No address associated with hostname"),
        )
    }

    @Test
    fun `connection refused maps to connection refused message`() {
        val e = ConnectException("Connection refused")
        assertEquals(
            "Connection refused. The server may be down or the port may be incorrect.",
            ErrorUtils.formatError(e),
        )
    }

    @Test
    fun `connectexception class name maps to connection refused message`() {
        assertEquals(
            "Connection refused. The server may be down or the port may be incorrect.",
            ErrorUtils.formatErrorMessage("java.net.ConnectException: failed to connect"),
        )
    }

    @Test
    fun `socket timeout maps to timed out message`() {
        val e = SocketTimeoutException("Read timed out")
        assertEquals(
            "Connection timed out. The server is not responding.",
            ErrorUtils.formatError(e),
        )
    }

    @Test
    fun `timeout keyword maps to timed out message`() {
        assertEquals(
            "Connection timed out. The server is not responding.",
            ErrorUtils.formatErrorMessage("timeout while connecting"),
        )
    }

    @Test
    fun `connection reset maps to try again message`() {
        assertEquals(
            "Connection was reset. Please try again.",
            ErrorUtils.formatErrorMessage("java.net.SocketException: Connection reset"),
        )
    }

    @Test
    fun `no route to host maps to check network message`() {
        assertEquals(
            "Cannot reach the server. Check your network connection.",
            ErrorUtils.formatErrorMessage("java.net.NoRouteToHostException: No route to host"),
        )
    }

    // --- SSL/TLS errors ---

    @Test
    fun `ssl handshake failure maps to secure connection message`() {
        val e = SSLHandshakeException("Handshake failed")
        // formatError uses e.message; the keyword match is on message content
        assertEquals(
            "Secure connection failed. There may be a certificate issue with the server.",
            ErrorUtils.formatErrorMessage("SSLHandshakeException: " + (e.message ?: "")),
        )
    }

    @Test
    fun `certificate keyword maps to secure connection message`() {
        assertEquals(
            "Secure connection failed. There may be a certificate issue with the server.",
            ErrorUtils.formatErrorMessage("certificate verify failed"),
        )
    }

    // --- S3 authentication errors ---

    @Test
    fun `invalidaccesskeyid maps to invalid access key`() {
        assertEquals(
            "Invalid access key. Please check your credentials.",
            ErrorUtils.formatErrorMessage("InvalidAccessKeyId: The AWS access key Id is wrong"),
        )
    }

    @Test
    fun `signaturedoesnotmatch maps to invalid secret key`() {
        assertEquals(
            "Invalid secret key. Please verify your credentials.",
            ErrorUtils.formatErrorMessage("SignatureDoesNotMatch: signature mismatch"),
        )
    }

    @Test
    fun `accessdenied maps to permission message`() {
        assertEquals(
            "Access denied. You don't have permission to perform this action.",
            ErrorUtils.formatErrorMessage("AccessDenied: Access Denied"),
        )
    }

    @Test
    fun `expired token maps to session expired message`() {
        assertEquals(
            "Your session has expired. Please re-authenticate.",
            ErrorUtils.formatErrorMessage("The provided token has expired"),
        )
    }

    // --- S3 bucket errors ---

    @Test
    fun `nosuchbucket maps to bucket missing message`() {
        assertEquals(
            "The bucket does not exist or you don't have access to it.",
            ErrorUtils.formatErrorMessage("NoSuchBucket: The specified bucket does not exist"),
        )
    }

    @Test
    fun `bucketalreadyexists maps to duplicate bucket message`() {
        assertEquals(
            "A bucket with this name already exists.",
            ErrorUtils.formatErrorMessage("BucketAlreadyExists: another account owns this name"),
        )
    }

    @Test
    fun `bucketalreadyownedby maps to duplicate bucket message`() {
        assertEquals(
            "A bucket with this name already exists.",
            ErrorUtils.formatErrorMessage("BucketAlreadyOwnedByYou"),
        )
    }

    @Test
    fun `bucketnotempty maps to empty-first message`() {
        assertEquals(
            "Cannot delete bucket. The bucket must be empty first.",
            ErrorUtils.formatErrorMessage("BucketNotEmpty: The bucket you tried to delete is not empty"),
        )
    }

    @Test
    fun `toomanybuckets maps to max buckets message`() {
        assertEquals(
            "Cannot create bucket. You have reached the maximum number of buckets.",
            ErrorUtils.formatErrorMessage("TooManyBuckets"),
        )
    }

    // --- S3 object errors ---

    @Test
    fun `nosuchkey maps to file missing message`() {
        assertEquals(
            "The file does not exist or has been deleted.",
            ErrorUtils.formatErrorMessage("NoSuchKey: The specified key does not exist"),
        )
    }

    @Test
    fun `entitytoolarge maps to file too large message`() {
        assertEquals(
            "The file is too large to upload.",
            ErrorUtils.formatErrorMessage("EntityTooLarge: Your proposed upload exceeds the maximum"),
        )
    }

    @Test
    fun `too large phrase maps to file too large message`() {
        assertEquals(
            "The file is too large to upload.",
            ErrorUtils.formatErrorMessage("upload is too large"),
        )
    }

    @Test
    fun `invalidbucketname maps to naming rules message`() {
        assertEquals(
            "Invalid bucket name. Use only lowercase letters, numbers, and hyphens.",
            ErrorUtils.formatErrorMessage("InvalidBucketName: Bad_Name"),
        )
    }

    @Test
    fun `key too long phrase maps to path too long message`() {
        assertEquals(
            "The file path is too long.",
            ErrorUtils.formatErrorMessage("AWS Error: your key too long for this request"),
        )
    }

    @Test
    fun `S3 KeyTooLong code maps to path too long message`() {
        assertEquals(
            "The file path is too long.",
            ErrorUtils.formatErrorMessage("KeyTooLong: Your key is too long"),
        )
    }

    // --- Rate limiting ---

    @Test
    fun `slowdown maps to too many requests message`() {
        assertEquals(
            "Too many requests. Please wait a moment and try again.",
            ErrorUtils.formatErrorMessage("SlowDown: Please reduce your request rate"),
        )
    }

    @Test
    fun `toomanyrequests maps to too many requests message`() {
        assertEquals(
            "Too many requests. Please wait a moment and try again.",
            ErrorUtils.formatErrorMessage("TooManyRequests"),
        )
    }

    @Test
    fun `rate limit phrase maps to too many requests message`() {
        assertEquals(
            "Too many requests. Please wait a moment and try again.",
            ErrorUtils.formatErrorMessage("rate limit exceeded"),
        )
    }

    // --- Storage errors ---

    @Test
    fun `insufficientstorage maps to quota message`() {
        assertEquals(
            "Storage quota exceeded. Free up some space and try again.",
            ErrorUtils.formatErrorMessage("InsufficientStorage"),
        )
    }

    @Test
    fun `quotaexceeded maps to quota message`() {
        assertEquals(
            "Storage quota exceeded. Free up some space and try again.",
            ErrorUtils.formatErrorMessage("QuotaExceeded"),
        )
    }

    // --- Service errors ---

    @Test
    fun `serviceunavailable maps to server issues message`() {
        assertEquals(
            "The server is experiencing issues. Please try again later.",
            ErrorUtils.formatErrorMessage("ServiceUnavailable"),
        )
    }

    @Test
    fun `internal server error maps to server issues message`() {
        assertEquals(
            "The server is experiencing issues. Please try again later.",
            ErrorUtils.formatErrorMessage("Internal Server Error"),
        )
    }

    // --- Client init / network misc ---

    @Test
    fun `not initialized maps to configure account message`() {
        assertEquals(
            "Not connected. Please configure your S3 account first.",
            ErrorUtils.formatErrorMessage("S3 client not initialized"),
        )
    }

    @Test
    fun `network unreachable maps to network message`() {
        assertEquals(
            "Network is unreachable. Please check your connection.",
            ErrorUtils.formatErrorMessage("Network is unreachable: connect"),
        )
    }

    // --- Fallback cleanup ---

    @Test
    fun `unknown message is capitalized and terminated with a period`() {
        assertEquals(
            "Something weird happened.",
            ErrorUtils.formatErrorMessage("something weird happened"),
        )
    }

    @Test
    fun `cleanup strips package prefixes and exception class names`() {
        assertEquals(
            "Oops.",
            ErrorUtils.formatErrorMessage("java.lang.Exception: oops"),
        )
    }

    @Test
    fun `empty message falls back to generic message`() {
        assertEquals(
            "An unexpected error occurred. Please try again.",
            ErrorUtils.formatErrorMessage(""),
        )
    }

    @Test
    fun `null message falls back to exception simple name`() {
        // Pin current behavior: an exception with no message formats as its
        // class simple name (no keyword match → cleanup path), not a friendly string.
        assertEquals("Exception.", ErrorUtils.formatError(Exception()))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(
            "The bucket does not exist or you don't have access to it.",
            ErrorUtils.formatErrorMessage("NOSUCHBUCKET"),
        )
    }
}
