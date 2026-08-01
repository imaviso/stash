package com.imaviso.stash.data

import com.imaviso.stash.data.model.FileType
import com.imaviso.stash.data.model.FileTypeStats
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.remote.FakeS3Operations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ObjectOperations] driven through the [com.imaviso.stash.data.remote.S3Operations]
 * port against the in-memory [FakeS3Operations] adapter.
 *
 * Not covered: [ObjectOperations.downloadFolder] — it takes an Android
 * [android.content.Context] and writes via [com.imaviso.stash.util.DownloadsSaver]
 * (MediaStore), so it is not JVM-testable here.
 */
class ObjectOperationsTest {
    private fun newPair(): Pair<FakeS3Operations, ObjectOperations> {
        val fake = FakeS3Operations()
        return fake to ObjectOperations(fake)
    }

    // ==================== RECURSIVE DELETE ====================

    @Test
    fun `recursiveDeleteFolder removes nested objects and the folder marker`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("media")
            fake.seedFolder("media", "pics/")
            fake.seedObject("media", "pics/cats/a.jpg", ByteArray(10))
            fake.seedObject("media", "pics/cats/b.jpg", ByteArray(20))
            fake.seedObject("media", "pics/dogs/2024/c.jpg", ByteArray(30))

            val result = ops.recursiveDeleteFolder("media", "pics/")

            assertTrue(result.isSuccess)
            assertTrue(fake.keys("media").isEmpty())
        }

    @Test
    fun `recursiveDeleteFolder leaves sibling prefixes untouched`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("media")
            fake.seedFolder("media", "pics/")
            fake.seedObject("media", "pics/a.jpg", ByteArray(1))
            fake.seedObject("media", "pics-backup/b.jpg", ByteArray(2))
            fake.seedObject("media", "keep.txt", ByteArray(3))

            ops.recursiveDeleteFolder("media", "pics/").getOrThrow()

            assertEquals(setOf("pics-backup/b.jpg", "keep.txt"), fake.keys("media"))
        }

    // ==================== PASTE / COPY ====================

    @Test
    fun `pasteObjects copies across prefixes, keeping sources when not moving`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("b")
            fake.seedObject("b", "src/x.txt", ByteArray(5))
            fake.seedObject("b", "src/y.txt", ByteArray(6))

            val progress = mutableListOf<Pair<Int, Int>>()
            val failures =
                ops.pasteObjects(
                    sourceBucket = "b",
                    destBucket = "b",
                    destPrefix = "dst/",
                    objects = listOf(S3Object("src/x.txt", 5), S3Object("src/y.txt", 6)),
                    deleteSourceAfterCopy = false,
                    onProgress = { success, total -> progress.add(success to total) },
                )

            assertEquals(0, failures)
            assertTrue(fake.hasObject("b", "src/x.txt"))
            assertTrue(fake.hasObject("b", "src/y.txt"))
            assertTrue(fake.hasObject("b", "dst/x.txt"))
            assertTrue(fake.hasObject("b", "dst/y.txt"))
            assertEquals(listOf(1 to 2, 2 to 2), progress)
        }

    @Test
    fun `pasteObjects deletes sources when moving`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("b")
            fake.seedObject("b", "src/x.txt", ByteArray(5))

            val failures =
                ops.pasteObjects(
                    sourceBucket = "b",
                    destBucket = "b",
                    destPrefix = "archive/2024/",
                    objects = listOf(S3Object("src/x.txt", 5)),
                    deleteSourceAfterCopy = true,
                    onProgress = { _, _ -> },
                )

            assertEquals(0, failures)
            assertFalse(fake.hasObject("b", "src/x.txt"))
            assertTrue(fake.hasObject("b", "archive/2024/x.txt"))
        }

    @Test
    fun `pasteObjects counts copy failures and keeps going`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("b")
            fake.seedObject("b", "src/present.txt", ByteArray(1))

            val failures =
                ops.pasteObjects(
                    sourceBucket = "b",
                    destBucket = "b",
                    destPrefix = "dst/",
                    objects = listOf(
                        S3Object("src/missing.txt", 1),
                        S3Object("src/present.txt", 1),
                    ),
                    deleteSourceAfterCopy = false,
                    onProgress = { _, _ -> },
                )

            assertEquals(1, failures)
            assertTrue(fake.hasObject("b", "dst/present.txt"))
            assertFalse(fake.hasObject("b", "dst/missing.txt"))
        }

    // ==================== RENAME ====================

    @Test
    fun `renameObject copies to the new key and deletes the old one`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("b")
            fake.seedObject("b", "photos/cat.jpg", ByteArray(42))

            val result = ops.renameObject("b", "photos/cat.jpg", "photos/dog.jpg")

            assertTrue(result.isSuccess)
            assertFalse(fake.hasObject("b", "photos/cat.jpg"))
            assertTrue(fake.hasObject("b", "photos/dog.jpg"))
            assertEquals(42, fake.listObjectsRecursive("b").getOrThrow().single().size)
        }

    // ==================== STORAGE STATS ====================

    @Test
    fun `computeStorageStats counts files and folders and sums total size`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("b")
            fake.seedFolder("b", "img/")
            fake.seedFolder("b", "doc/")
            fake.seedObject("b", "img/a.jpg", ByteArray(100))
            fake.seedObject("b", "img/b.png", ByteArray(200))
            fake.seedObject("b", "doc/readme.txt", ByteArray(50))
            fake.seedObject("b", "doc/paper.pdf", ByteArray(1000))

            val stats = ops.computeStorageStats("b", "").getOrThrow()

            assertEquals(4, stats.fileCount)
            assertEquals(2, stats.folderCount)
            assertEquals(1350L, stats.totalSize)
        }

    @Test
    fun `computeStorageStats groups counts and sizes by file type`() =
        runTest {
            val (fake, ops) = newPair()
            fake.seedBucket("b")
            fake.seedFolder("b", "img/")
            fake.seedObject("b", "img/a.jpg", ByteArray(100))
            fake.seedObject("b", "img/b.png", ByteArray(200))
            fake.seedObject("b", "notes.txt", ByteArray(50))
            fake.seedObject("b", "paper.pdf", ByteArray(1000))
            fake.seedObject("b", "archive.bin", ByteArray(7))

            val stats = ops.computeStorageStats("b", "").getOrThrow()

            assertEquals(FileTypeStats(2, 300L), stats.byFileType[FileType.IMAGE])
            assertEquals(FileTypeStats(1, 50L), stats.byFileType[FileType.TEXT])
            assertEquals(FileTypeStats(1, 1000L), stats.byFileType[FileType.PDF])
            assertEquals(FileTypeStats(1, 7L), stats.byFileType[FileType.OTHER])
        }
}
