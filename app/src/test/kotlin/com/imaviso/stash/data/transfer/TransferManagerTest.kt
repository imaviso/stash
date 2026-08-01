package com.imaviso.stash.data.transfer

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the transfer module through its interface: record store invariants,
 * the >5MB routing rule, dispatch selection via the fake (in-memory test)
 * executor, and the cancel-folder-download semantics that made the
 * CANCELLED → COMPLETED overwrite structurally impossible.
 *
 * The WorkManager adapter itself is Android-bound and untested here.
 */
class TransferManagerTest {
    private fun newManager(executor: FakeTransferExecutor = FakeTransferExecutor()): TransferManager =
        TransferManager(context = null, executorFactory = { executor })

    private fun transfer(
        id: String,
        state: TransferState = TransferState.ACTIVE,
        progress: Int = 0,
        bytesTransferred: Long = 0,
        totalBytes: Long = 0,
    ) = TransferInfo(
        id = id,
        fileName = "$id.dat",
        type = TransferType.UPLOAD,
        progress = progress,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        state = state,
        bucketName = "bucket",
    )

    private fun TransferManager.terminalize(
        id: String,
        state: TransferState,
        error: String? = null,
        totalBytes: Long = 0,
        bytesTransferred: Long = 0,
    ) = store.markTerminal(
        id = id,
        state = state,
        fileName = "$id.dat",
        type = TransferType.UPLOAD,
        bucketName = "bucket",
        error = error,
        totalBytes = totalBytes,
        bytesTransferred = bytesTransferred,
    )

    // --- upsert ---

    @Test
    fun `upsert appends unknown id`() {
        val tm = newManager()
        tm.store.upsert(transfer("a"))
        tm.store.upsert(transfer("b"))

        assertEquals(setOf("a", "b"), tm.records.value.keys)
        assertEquals(2, tm.records.value.size)
    }

    @Test
    fun `upsert replaces record with same id without duplicating`() {
        val tm = newManager()
        tm.store.upsert(transfer("a", progress = 10))
        tm.store.upsert(transfer("a", progress = 50))

        assertEquals(1, tm.records.value.size)
        assertEquals(50, tm.records.value.getValue("a").progress)
    }

    @Test
    fun `records flow emits initial state then upserted records`() =
        runTest {
            val tm = newManager()
            tm.records.test {
                assertTrue(awaitItem().isEmpty())

                tm.store.upsert(transfer("a", progress = 25))
                assertEquals(25, awaitItem().getValue("a").progress)

                tm.store.upsert(transfer("a", progress = 75))
                assertEquals(75, awaitItem().getValue("a").progress)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `activeTransfers filters to ACTIVE state only`() =
        runTest {
            val tm = newManager()
            tm.store.upsert(transfer("active", state = TransferState.ACTIVE))
            tm.store.upsert(transfer("done", state = TransferState.COMPLETED))
            tm.store.upsert(transfer("failed", state = TransferState.FAILED))

            assertEquals(listOf("active"), tm.activeTransfers.first().map { it.id })
            assertEquals(1, tm.activeTransferCount.first())
        }

    // --- markTerminal on existing record ---

    @Test
    fun `markTerminal completes active transfer with progress 100`() {
        val tm = newManager()
        tm.store.upsert(transfer("a", progress = 42, bytesTransferred = 420, totalBytes = 1000))
        tm.terminalize("a", TransferState.COMPLETED)

        val record = tm.records.value.getValue("a")
        assertEquals(TransferState.COMPLETED, record.state)
        assertEquals(100, record.progress)
        assertEquals("Completed", record.status)
        // Zero byte arguments keep the existing counters
        assertEquals(420, record.bytesTransferred)
        assertEquals(1000, record.totalBytes)
        assertNull(record.error)
    }

    @Test
    fun `markTerminal FAILED keeps partial progress and records error`() {
        val tm = newManager()
        tm.store.upsert(transfer("a", progress = 42))
        tm.terminalize("a", TransferState.FAILED, error = "boom")

        val record = tm.records.value.getValue("a")
        assertEquals(TransferState.FAILED, record.state)
        assertEquals(42, record.progress)
        assertEquals("Failed", record.status)
        assertEquals("boom", record.error)
    }

    @Test
    fun `markTerminal overrides byte counters when positive values given`() {
        val tm = newManager()
        tm.store.upsert(transfer("a", bytesTransferred = 1, totalBytes = 2))
        tm.terminalize("a", TransferState.COMPLETED, totalBytes = 500, bytesTransferred = 500)

        val record = tm.records.value.getValue("a")
        assertEquals(500, record.bytesTransferred)
        assertEquals(500, record.totalBytes)
    }

    @Test
    fun `markTerminal CANCELLED produces capitalized status`() {
        val tm = newManager()
        tm.store.upsert(transfer("a"))
        tm.terminalize("a", TransferState.CANCELLED)
        assertEquals("Cancelled", tm.records.value.getValue("a").status)
    }

    // --- markTerminal on unknown id ---

    @Test
    fun `markTerminal inserts COMPLETED record with progress 100 when id is unknown`() {
        // New pinned invariant: a terminal COMPLETED record is never 0%.
        val tm = newManager()
        tm.terminalize("late", TransferState.COMPLETED)

        val record = tm.records.value.getValue("late")
        assertEquals(TransferState.COMPLETED, record.state)
        assertEquals(100, record.progress)
        assertEquals("bucket", record.bucketName)
    }

    // --- terminal-final invariant ---

    @Test
    fun `terminal states are final - later markTerminal is ignored`() {
        // Replaces the old pin "markTerminal currently overwrites terminal state".
        val tm = newManager()
        tm.terminalize("a", TransferState.COMPLETED)
        tm.terminalize("a", TransferState.FAILED, error = "late failure")

        val record = tm.records.value.getValue("a")
        assertEquals(TransferState.COMPLETED, record.state)
        assertEquals("Completed", record.status)
        assertEquals(100, record.progress)
        assertNull(record.error)
    }

    @Test
    fun `upsert of late progress onto a terminal record is ignored`() {
        val tm = newManager()
        tm.terminalize("a", TransferState.CANCELLED)

        tm.store.upsert(transfer("a", progress = 99, bytesTransferred = 999))

        val record = tm.records.value.getValue("a")
        assertEquals(TransferState.CANCELLED, record.state)
        assertEquals(0, record.progress)
    }

    // --- cancel semantics (the folder-download overwrite bug) ---

    @Test
    fun `cancel then late completion keeps CANCELLED - the overwrite bug`() {
        val executor = FakeTransferExecutor()
        val tm = newManager(executor)

        // Folder downloads run on the in-process adapter with a registered job.
        val transferId = tm.enqueueFolderDownload(bucket = "bucket", folderKey = "photos/", folderName = "photos")
        assertTrue(executor.launchedTransferIds.contains(transferId))
        assertEquals(TransferState.ACTIVE, tm.records.value.getValue(transferId).state)

        // User cancels from the Transfers screen: the record goes CANCELLED
        // and the cancellation reaches the in-process job registry.
        tm.cancel(transferId)
        assertTrue(executor.cancelledTransferIds.contains(transferId))
        assertEquals(TransferState.CANCELLED, tm.records.value.getValue(transferId).state)

        // The still-running (un-cancelled) download would call
        // recordTerminal(COMPLETED); the terminal-final invariant blocks it.
        tm.terminalize(transferId, TransferState.COMPLETED)
        assertEquals(TransferState.CANCELLED, tm.records.value.getValue(transferId).state)
    }

    @Test
    fun `cancel marks an active transfer CANCELLED and delegates to the executor`() {
        val executor = FakeTransferExecutor()
        val tm = newManager(executor)

        val id = tm.enqueueUpload("content://x/a.jpg", "bucket", "", "a.jpg", size = 10L, contentType = "image/jpeg")
        tm.cancel(id)

        assertEquals(TransferState.CANCELLED, tm.records.value.getValue(id).state)
        assertEquals(listOf(id), executor.cancelledTransferIds)
    }

    @Test
    fun `cancel of an unknown id only delegates to the executor`() {
        val executor = FakeTransferExecutor()
        val tm = newManager(executor)

        tm.cancel("nope")

        assertEquals(listOf("nope"), executor.cancelledTransferIds)
        assertTrue(tm.records.value.isEmpty())
    }

    // --- routing rule ---

    @Test
    fun `background routing rule - exactly 5MB foreground, above background`() {
        val tm = newManager()
        assertFalse(tm.shouldRunInBackground(TransferManager.BACKGROUND_THRESHOLD_BYTES))
        assertTrue(tm.shouldRunInBackground(TransferManager.BACKGROUND_THRESHOLD_BYTES + 1))
    }

    @Test
    fun `enqueueUpload default routes large files to the background adapter`() {
        val executor = FakeTransferExecutor()
        val tm = newManager(executor)

        val id =
            tm.enqueueUpload(
                fileUri = "content://x/big.bin",
                bucket = "bucket",
                prefix = "dir/",
                fileName = "big.bin",
                size = TransferManager.BACKGROUND_THRESHOLD_BYTES + 1,
                contentType = "application/octet-stream",
            )

        assertEquals(1, executor.enqueuedUploads.size)
        assertTrue(executor.launchedTransferIds.isEmpty())
        val call = executor.enqueuedUploads.single()
        assertEquals(id, call.transferId)
        assertEquals("bucket", call.bucketName)
        assertEquals("dir/big.bin", call.objectKey)
        assertEquals("content://x/big.bin", call.fileUri)

        val record = tm.records.value.getValue(id)
        assertEquals(TransferState.ACTIVE, record.state)
        assertEquals(TransferType.UPLOAD, record.type)
        assertEquals("Preparing...", record.status)
        assertEquals("bucket", record.bucketName)
    }

    @Test
    fun `enqueueUpload default runs small files on the in-process adapter`() {
        val executor = FakeTransferExecutor()
        val tm = newManager(executor)

        val id =
            tm.enqueueUpload(
                fileUri = "content://x/small.jpg",
                bucket = "bucket",
                prefix = "",
                fileName = "small.jpg",
                size = 100L,
                contentType = "image/jpeg",
            )

        assertTrue(executor.enqueuedUploads.isEmpty())
        assertEquals(listOf(id), executor.launchedTransferIds)

        val record = tm.records.value.getValue(id)
        assertEquals(TransferState.ACTIVE, record.state)
        assertEquals("Uploading...", record.status)
        assertEquals(100L, record.totalBytes)
    }

    @Test
    fun `enqueueDownload creates a record and dispatches to the background adapter`() {
        val executor = FakeTransferExecutor()
        val tm = newManager(executor)

        val id = tm.enqueueDownload("bucket", key = "photos/cat.jpg", fileName = "cat.jpg", size = 1234L, mimeType = "image/jpeg")

        val call = executor.enqueuedDownloads.single()
        assertEquals(id, call.transferId)
        assertEquals("photos/cat.jpg", call.objectKey)
        assertEquals(1234L, call.fileSize)
        assertEquals("image/jpeg", call.mimeType)

        val record = tm.records.value.getValue(id)
        assertEquals(TransferType.DOWNLOAD, record.type)
        assertEquals(TransferState.ACTIVE, record.state)
        assertEquals("Preparing...", record.status)
        assertEquals(1234L, record.totalBytes)
    }

    // --- clearHistory ---

    @Test
    fun `clearHistory drops terminal records and keeps active`() {
        val tm = newManager()
        tm.store.upsert(transfer("active", state = TransferState.ACTIVE))
        tm.store.upsert(transfer("done", state = TransferState.COMPLETED))
        tm.store.upsert(transfer("failed", state = TransferState.FAILED))

        tm.clearHistory()

        assertEquals(listOf("active"), tm.records.value.keys.toList())
    }
}
