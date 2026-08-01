package com.imaviso.stash.data.model

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class S3ObjectTest {
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // --- fileName ---

    @Test
    fun `fileName of nested file is last segment`() {
        assertEquals("cat.jpg", S3Object(key = "photos/2024/cat.jpg").fileName)
    }

    @Test
    fun `fileName of root-level file is the key itself`() {
        assertEquals("cat.jpg", S3Object(key = "cat.jpg").fileName)
    }

    @Test
    fun `fileName of nested folder strips trailing slash first`() {
        assertEquals("2024", S3Object(key = "photos/2024/").fileName)
    }

    @Test
    fun `fileName of root-level folder is segment without slash`() {
        assertEquals("photos", S3Object(key = "photos/").fileName)
    }

    // --- isFolder ---

    @Test
    fun `trailing slash marks folder`() {
        assertTrue(S3Object(key = "photos/2024/").isFolder)
    }

    @Test
    fun `no trailing slash is not a folder`() {
        assertFalse(S3Object(key = "photos/2024/cat.jpg").isFolder)
    }

    // --- extension ---

    @Test
    fun `extension is lowercased`() {
        assertEquals("jpg", S3Object(key = "photos/CAT.JPG").extension)
    }

    @Test
    fun `extension is empty when key has no dot`() {
        assertEquals("", S3Object(key = "photos/Makefile").extension)
    }

    @Test
    fun `dotfile name becomes its own extension`() {
        // substringAfterLast on ".gitignore" yields "gitignore"
        assertEquals("gitignore", S3Object(key = ".gitignore").extension)
    }

    // --- fileType ---

    @Test
    fun `image extensions map to IMAGE`() {
        assertEquals(FileType.IMAGE, S3Object(key = "a.jpg").fileType)
        assertEquals(FileType.IMAGE, S3Object(key = "a.png").fileType)
        assertEquals(FileType.IMAGE, S3Object(key = "a.HEIC").fileType)
    }

    @Test
    fun `video extensions map to VIDEO`() {
        assertEquals(FileType.VIDEO, S3Object(key = "a.mp4").fileType)
        assertEquals(FileType.VIDEO, S3Object(key = "a.mkv").fileType)
        assertEquals(FileType.VIDEO, S3Object(key = "a.WEBM").fileType)
    }

    @Test
    fun `audio extensions map to AUDIO`() {
        assertEquals(FileType.AUDIO, S3Object(key = "a.mp3").fileType)
        assertEquals(FileType.AUDIO, S3Object(key = "a.flac").fileType)
    }

    @Test
    fun `text extensions map to TEXT`() {
        assertEquals(FileType.TEXT, S3Object(key = "a.txt").fileType)
        assertEquals(FileType.TEXT, S3Object(key = "a.md").fileType)
        assertEquals(FileType.TEXT, S3Object(key = "a.csv").fileType)
    }

    @Test
    fun `pdf maps to PDF`() {
        assertEquals(FileType.PDF, S3Object(key = "doc.pdf").fileType)
    }

    @Test
    fun `unknown extension maps to OTHER`() {
        assertEquals(FileType.OTHER, S3Object(key = "a.bin").fileType)
        assertEquals(FileType.OTHER, S3Object(key = "a.zip").fileType)
        assertEquals(FileType.OTHER, S3Object(key = "Makefile").fileType)
    }

    // --- mimeType ---

    @Test
    fun `image mime types`() {
        assertEquals("image/jpeg", S3Object(key = "a.jpg").mimeType)
        assertEquals("image/jpeg", S3Object(key = "a.jpeg").mimeType)
        assertEquals("image/png", S3Object(key = "a.png").mimeType)
        assertEquals("image/svg+xml", S3Object(key = "a.svg").mimeType)
    }

    @Test
    fun `video mime types`() {
        assertEquals("video/mp4", S3Object(key = "a.mp4").mimeType)
        assertEquals("video/x-matroska", S3Object(key = "a.mkv").mimeType)
    }

    @Test
    fun `audio mime types`() {
        assertEquals("audio/mpeg", S3Object(key = "a.mp3").mimeType)
        assertEquals("audio/wav", S3Object(key = "a.wav").mimeType)
    }

    @Test
    fun `text mime types`() {
        assertEquals("text/plain", S3Object(key = "a.txt").mimeType)
        assertEquals("application/json", S3Object(key = "a.json").mimeType)
        assertEquals("text/markdown", S3Object(key = "a.md").mimeType)
    }

    @Test
    fun `pdf mime type`() {
        assertEquals("application/pdf", S3Object(key = "doc.pdf").mimeType)
    }

    @Test
    fun `unknown extension mime type is octet-stream`() {
        assertEquals("application/octet-stream", S3Object(key = "a.bin").mimeType)
        assertEquals("application/octet-stream", S3Object(key = "Makefile").mimeType)
    }

    // --- isPreviewable ---

    @Test
    fun `image video audio text pdf are previewable`() {
        assertTrue(S3Object(key = "a.jpg").isPreviewable)
        assertTrue(S3Object(key = "a.mp4").isPreviewable)
        assertTrue(S3Object(key = "a.mp3").isPreviewable)
        assertTrue(S3Object(key = "a.txt").isPreviewable)
        assertTrue(S3Object(key = "a.pdf").isPreviewable)
    }

    @Test
    fun `other file type is not previewable`() {
        assertFalse(S3Object(key = "a.zip").isPreviewable)
    }

    // --- formattedSize pass-through ---

    @Test
    fun `formattedSize delegates to FormatUtils`() {
        assertEquals("2.0 KB", S3Object(key = "a.txt", size = 2048).formattedSize)
        assertEquals("0 B", S3Object(key = "photos/").formattedSize)
    }
}
