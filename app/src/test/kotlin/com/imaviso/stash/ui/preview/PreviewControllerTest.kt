package com.imaviso.stash.ui.preview

import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.remote.FakeS3Operations
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PreviewController] policy driven through the
 * [com.imaviso.stash.data.remote.S3Operations] port against the in-memory
 * [FakeS3Operations] adapter. Covers the routing table (stream vs bytes),
 * the 10MB in-memory cap boundary, and TooLarge/Unsupported/Error cases.
 */
class PreviewControllerTest {
    // ==================== STREAM ROUTING ====================

    @Test
    fun `video streams via presigned URL with mime type`() =
        runTest {
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "a.mp4", ByteArray(3), "video/mp4") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("a.mp4", size = 3)), 0, this)
            advanceUntilIdle()

            val source = controller.state.value.source
            assertTrue(source is PreviewSource.Stream)
            source as PreviewSource.Stream
            assertTrue(source.url.contains(BUCKET) && source.url.contains("a.mp4"))
            assertEquals("video/mp4", source.mimeType)
        }

    @Test
    fun `image and audio also stream`() =
        runTest {
            val fake =
                FakeS3Operations().apply {
                    seedObject(BUCKET, "a.jpg", ByteArray(3), "image/jpeg")
                    seedObject(BUCKET, "b.mp3", ByteArray(3), "audio/mpeg")
                }
            val objects = listOf(S3Object("a.jpg", size = 3), S3Object("b.mp3", size = 3))
            val controller = PreviewController(fake, BUCKET, objects, 0, this)
            advanceUntilIdle()
            assertTrue(controller.state.value.source is PreviewSource.Stream)

            controller.select(1)
            advanceUntilIdle()
            val source = controller.state.value.source
            assertTrue(source is PreviewSource.Stream)
            assertEquals("audio/mpeg", (source as PreviewSource.Stream).mimeType)
        }

    // ==================== BYTES ROUTING ====================

    @Test
    fun `text loads whole bytes`() =
        runTest {
            val content = "hello stash".toByteArray()
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "notes.txt", content, "text/plain") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("notes.txt", size = content.size.toLong())), 0, this)
            advanceUntilIdle()

            val source = controller.state.value.source
            assertTrue(source is PreviewSource.Bytes)
            source as PreviewSource.Bytes
            assertTrue(content.contentEquals(source.data))
            assertEquals("text/plain", source.mimeType)
        }

    @Test
    fun `pdf loads whole bytes`() =
        runTest {
            val content = ByteArray(100) { it.toByte() }
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "doc.pdf", content, "application/pdf") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("doc.pdf", size = 100)), 0, this)
            advanceUntilIdle()

            assertTrue(controller.state.value.source is PreviewSource.Bytes)
        }

    // ==================== CAP BOUNDARY ====================

    @Test
    fun `exactly at the 10MB cap still loads bytes`() =
        runTest {
            val content = ByteArray(CAP.toInt()) { 1 }
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "big.txt", content, "text/plain") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("big.txt", size = CAP)), 0, this)
            advanceUntilIdle()

            assertTrue(controller.state.value.source is PreviewSource.Bytes)
        }

    @Test
    fun `one byte over the 10MB cap is TooLarge`() =
        runTest {
            val overCap = CAP + 1
            val content = ByteArray(overCap.toInt()) { 1 }
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "huge.txt", content, "text/plain") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("huge.txt", size = overCap)), 0, this)

            assertEquals(PreviewSource.TooLarge(overCap), controller.state.value.source)
        }

    @Test
    fun `media streams regardless of size - cap applies to bytes route only`() =
        runTest {
            val overCap = CAP + 1
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "movie.mp4", ByteArray(1), "video/mp4") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("movie.mp4", size = overCap)), 0, this)
            advanceUntilIdle()

            assertTrue(controller.state.value.source is PreviewSource.Stream)
        }

    // ==================== OTHER / UNSUPPORTED ====================

    @Test
    fun `other file types under cap load bytes - screen renders Unsupported from it`() =
        runTest {
            val content = ByteArray(64) { 7 }
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "blob.bin", content) }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("blob.bin", size = 64)), 0, this)
            advanceUntilIdle()

            assertTrue(controller.state.value.source is PreviewSource.Bytes)
        }

    @Test
    fun `other file types over cap are TooLarge`() =
        runTest {
            val overCap = CAP + 1
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "blob.bin", ByteArray(1)) }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("blob.bin", size = overCap)), 0, this)

            assertEquals(PreviewSource.TooLarge(overCap), controller.state.value.source)
        }

    @Test
    fun `folder is Unsupported`() =
        runTest {
            val fake = FakeS3Operations().apply { seedFolder(BUCKET, "docs/") }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("docs/", size = 0)), 0, this)

            assertEquals(PreviewSource.Unsupported, controller.state.value.source)
        }

    // ==================== SESSION NAVIGATION / ERRORS ====================

    @Test
    fun `select ignores out-of-range indexes`() =
        runTest {
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "a.txt", ByteArray(1), "text/plain") }
            val objects = listOf(S3Object("a.txt", size = 1))
            val controller = PreviewController(fake, BUCKET, objects, 0, this)
            advanceUntilIdle()

            controller.select(5)
            controller.select(-1)
            assertEquals(0, controller.state.value.index)
        }

    @Test
    fun `missing object surfaces a source Error`() =
        runTest {
            val fake = FakeS3Operations().apply { seedBucket(BUCKET) }
            val controller = PreviewController(fake, BUCKET, listOf(S3Object("ghost.mp4", size = 3)), 0, this)
            advanceUntilIdle()

            val source = controller.state.value.source
            assertTrue(source is PreviewSource.Error)
            assertTrue((source as PreviewSource.Error).message.contains("ghost.mp4"))
        }

    @Test
    fun `initial index clamps into range`() =
        runTest {
            val fake = FakeS3Operations().apply { seedObject(BUCKET, "a.txt", ByteArray(1), "text/plain") }
            val objects = listOf(S3Object("a.txt", size = 1))
            val controller = PreviewController(fake, BUCKET, objects, 42, this)

            assertEquals(0, controller.state.value.index)
        }

    private companion object {
        const val BUCKET = "b"
        const val CAP = 10L * 1024 * 1024
    }
}
