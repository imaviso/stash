package com.imaviso.stash.data

import com.imaviso.stash.data.remote.FakeS3Operations
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingDeletes]: the commit timer runs on the injected scope
 * (process-wide in production, TestScope here) so it survives ViewModel
 * lifecycles; undo cancels the pending commit.
 */
class PendingDeletesTest {
    @Test
    fun `delete commits after the undo window`() =
        runTest {
            val fake = FakeS3Operations().apply { seedObject("b", "a.txt", ByteArray(1)) }
            val deletes = PendingDeletes(fake, this)

            deletes.schedule("b", "a.txt", windowMillis = 100)
            advanceTimeBy(99)
            assertTrue("still pending inside the window", fake.hasObject("b", "a.txt"))

            advanceTimeBy(2)
            advanceUntilIdle()
            assertTrue("committed after the window", !fake.hasObject("b", "a.txt"))
            assertNull(deletes.pending.value)
        }

    @Test
    fun `undo cancels the pending commit - object survives`() =
        runTest {
            val fake = FakeS3Operations().apply { seedObject("b", "a.txt", ByteArray(1)) }
            val deletes = PendingDeletes(fake, this)

            deletes.schedule("b", "a.txt", windowMillis = 100)
            advanceTimeBy(50)
            val cancelled = deletes.cancelPending()
            advanceUntilIdle()

            assertEquals(PendingDeletes.PendingDelete("b", "a.txt"), cancelled)
            assertTrue(fake.hasObject("b", "a.txt"))
            assertNull(deletes.pending.value)
        }

    @Test
    fun `cancel with nothing pending returns null`() =
        runTest {
            val deletes = PendingDeletes(FakeS3Operations(), this)
            assertNull(deletes.cancelPending())
        }

    @Test
    fun `scheduling a new delete cancels the previous pending one`() =
        runTest {
            val fake =
                FakeS3Operations().apply {
                    seedObject("b", "first.txt", ByteArray(1))
                    seedObject("b", "second.txt", ByteArray(1))
                }
            val deletes = PendingDeletes(fake, this)

            deletes.schedule("b", "first.txt", windowMillis = 100)
            advanceTimeBy(50)
            deletes.schedule("b", "second.txt", windowMillis = 100)

            advanceTimeBy(60)
            assertTrue("previous pending delete is cancelled, not committed", fake.hasObject("b", "first.txt"))

            advanceTimeBy(50)
            advanceUntilIdle()
            assertTrue(!fake.hasObject("b", "second.txt"))
        }

    @Test
    fun `commit outcomes are emitted`() =
        runTest {
            val fake = FakeS3Operations().apply { seedObject("b", "a.txt", ByteArray(1)) }
            val deletes = PendingDeletes(fake, this)

            val outcome = async { deletes.commits.first() }
            deletes.schedule("b", "a.txt", windowMillis = 100)
            advanceUntilIdle()

            val result = outcome.await()
            assertTrue(result is PendingDeletes.CommitResult.Succeeded)
            assertEquals("a.txt", result.delete.key)
        }

    @Test
    fun `commit failure surfaces as Failed and clears pending`() =
        runTest {
            val fake = FakeS3Operations() // no bucket seeded - delete throws
            val deletes = PendingDeletes(fake, this)

            val outcome = async { deletes.commits.first() }
            deletes.schedule("missing-bucket", "a.txt", windowMillis = 100)
            advanceUntilIdle()

            assertNull(deletes.pending.value)
            assertTrue(outcome.await() is PendingDeletes.CommitResult.Failed)
        }
    }
