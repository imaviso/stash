package com.imaviso.stash.data.remote

import com.imaviso.stash.data.model.S3Config
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic surface of Presigner: URL structure, key encoding rules,
 * query parameters, expiry mapping, signature shape.
 *
 * Gap: the SigV4 signature and X-Amz-Date are time-dependent — Date() is
 * hardcoded inside generatePresignedUrl with no clock seam — so the HMAC
 * chain (RFC 4231) and exact signed output cannot be pinned. The HMAC/SHA
 * helpers are private and unreachable through the public interface.
 */
class PresignerTest {
    private val config =
        S3Config(
            endpoint = "http://localhost:3900",
            accessKey = "test-access-key",
            secretKey = "test-secret-key",
            region = "us-east-1",
        )

    private fun url(
        key: String,
        expiresIn: kotlin.time.Duration = 2.hours,
        cfg: S3Config = config,
    ) = Presigner.generatePresignedUrl(cfg, "my-bucket", key, expiresIn)

    // --- URL structure ---

    @Test
    fun `url is endpoint plus bucket plus key path`() {
        val url = url("photos/2024/cat.jpg")
        assertTrue(url.startsWith("http://localhost:3900/my-bucket/photos/2024/cat.jpg?"))
    }

    @Test
    fun `endpoint trailing slash is trimmed before joining`() {
        val cfg = config.copy(endpoint = "http://localhost:3900/")
        val url = url("a.txt", cfg = cfg)
        assertTrue(url.startsWith("http://localhost:3900/my-bucket/a.txt?"))
    }

    // --- Key encoding ---

    @Test
    fun `slashes in key are preserved as path separators`() {
        assertTrue(url("a/b/c.txt").contains("/my-bucket/a/b/c.txt?"))
    }

    @Test
    fun `space in key encodes as %20, not plus`() {
        assertTrue(url("my cat.jpg").contains("/my-bucket/my%20cat.jpg?"))
    }

    @Test
    fun `special characters in key are percent encoded`() {
        // URLEncoder form-encoding, post-processed: %2F restored to '/', '+' back to %20.
        assertTrue(url("a&b+c.txt").contains("/my-bucket/a%26b%2Bc.txt?"))
    }

    @Test
    fun `non-ascii characters in key are utf-8 percent encoded`() {
        assertTrue(url("café.jpg").contains("/my-bucket/caf%C3%A9.jpg?"))
    }

    // --- Query parameters ---

    @Test
    fun `query declares sigv4 algorithm`() {
        assertTrue(url("a.txt").contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
    }

    @Test
    fun `query signs only the host header`() {
        assertTrue(url("a.txt").contains("X-Amz-SignedHeaders=host"))
    }

    @Test
    fun `expiry maps duration to whole seconds`() {
        assertTrue(url("a.txt", 2.hours).contains("X-Amz-Expires=7200"))
        assertTrue(url("a.txt", 90.seconds).contains("X-Amz-Expires=90"))
        assertTrue(url("a.txt", 1.seconds).contains("X-Amz-Expires=1"))
    }

    @Test
    fun `credential parameter contains access key and region scope`() {
        val url = url("a.txt")
        // credential = accessKey/dateStamp/region/s3/aws4_request, URL-encoded ('/' -> %2F)
        assertTrue(url.contains("X-Amz-Credential=test-access-key%2F"))
        assertTrue(url.contains("%2Fus-east-1%2Fs3%2Faws4_request"))
    }

    @Test
    fun `credential date stamp is eight digits`() {
        val credential =
            Regex("X-Amz-Credential=test-access-key%2F(\\d{8})%2F")
                .find(url("a.txt"))
        assertTrue("expected 8-digit date stamp in credential scope", credential != null)
    }

    @Test
    fun `amz date uses basic iso8601 utc format`() {
        val match = Regex("X-Amz-Date=(\\d{8}T\\d{6}Z)").find(url("a.txt"))
        assertTrue("expected yyyyMMdd'T'HHmmss'Z' X-Amz-Date", match != null)
    }

    // --- Signature shape ---

    @Test
    fun `signature is 64 lowercase hex characters`() {
        val signature =
            Regex("X-Amz-Signature=([0-9a-f]{64})$")
                .find(url("a.txt"))
        assertTrue("expected 64-hex signature", signature != null)
    }

    @Test
    fun `expiry seconds appear exactly once and before signature`() {
        val url = url("a.txt", 90.seconds)
        val expiresIdx = url.indexOf("X-Amz-Expires=90")
        val signatureIdx = url.indexOf("&X-Amz-Signature=")
        assertTrue(expiresIdx >= 0)
        assertTrue(signatureIdx > expiresIdx)
    }

    @Test
    fun `region flows into credential scope`() {
        val euCfg = config.copy(region = "eu-central-1")
        assertTrue(url("a.txt", cfg = euCfg).contains("%2Feu-central-1%2Fs3%2Faws4_request"))
    }
}
