package com.imaviso.stash.ui.viewmodel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavigationHistory] back-stack + persistence, driven through an injected
 * fake saver (the persistence seam; DataStore adapter stays in Android land).
 */
class NavigationHistoryTest {
    private class RecordingSaver {
        val calls = mutableListOf<Pair<String, NavState>>()

        suspend fun save(
            bucket: String,
            state: NavState,
        ) {
            calls.add(bucket to state)
        }
    }

    private fun newPair(): Pair<RecordingSaver, NavigationHistory> {
        val saver = RecordingSaver()
        return saver to NavigationHistory(saver::save)
    }

    @Test
    fun `attach without saved state starts at bucket root`() =
        runTest {
            val (_, nav) = newPair()
            nav.attach("b", null)

            assertEquals(NavState(currentPrefix = "", pathHistory = listOf("")), nav.state)
            assertFalse(nav.canGoUp)
        }

    @Test
    fun `attach restores saved state including scroll position`() =
        runTest {
            val (_, nav) = newPair()
            val saved = NavState(currentPrefix = "a/b/", pathHistory = listOf("", "a/", "a/b/"), scrollIndex = 7, scrollOffset = 42)
            nav.attach("b", saved)

            assertEquals(saved, nav.state)
            assertTrue(nav.canGoUp)
        }

    @Test
    fun `push appends to the back-stack, resets scroll and persists`() =
        runTest {
            val (saver, nav) = newPair()
            nav.attach("b", null)
            nav.push("a/")
            nav.push("a/b/")

            assertEquals(listOf("", "a/", "a/b/"), nav.state.pathHistory)
            assertEquals("a/b/", nav.state.currentPrefix)
            assertEquals(0, nav.state.scrollIndex)

            assertEquals(2, saver.calls.size)
            assertEquals("b" to nav.state, saver.calls.last())
        }

    @Test
    fun `pop returns null and persists nothing at root`() =
        runTest {
            val (saver, nav) = newPair()
            nav.attach("b", null)

            assertNull(nav.pop())
            assertTrue(saver.calls.isEmpty())
        }

    @Test
    fun `pop drops the last segment and persists`() =
        runTest {
            val (saver, nav) = newPair()
            nav.attach("b", NavState(currentPrefix = "a/", pathHistory = listOf("", "a/")))

            val popped = nav.pop()

            assertEquals("", popped?.currentPrefix)
            assertEquals(listOf(""), nav.state.pathHistory)
            assertFalse(nav.canGoUp)
            assertEquals("b" to nav.state, saver.calls.last())
        }

    @Test
    fun `jumpTo truncates the back-stack at the segment - breadcrumbs`() =
        runTest {
            val (saver, nav) = newPair()
            nav.attach(
                "b",
                NavState(currentPrefix = "a/b/c/", pathHistory = listOf("", "a/", "a/b/", "a/b/c/")),
            )

            val jumped = nav.jumpTo(1)

            assertEquals("a/", jumped?.currentPrefix)
            assertEquals(listOf("", "a/"), nav.state.pathHistory)
            assertEquals("b" to nav.state, saver.calls.last())
        }

    @Test
    fun `jumpTo out of range is a no-op`() =
        runTest {
            val (saver, nav) = newPair()
            nav.attach("b", null)

            assertNull(nav.jumpTo(5))
            assertNull(nav.jumpTo(-1))
            assertTrue(saver.calls.isEmpty())
        }

    @Test
    fun `saveScroll records and persists scroll position`() =
        runTest {
            val (saver, nav) = newPair()
            nav.attach("b", NavState(currentPrefix = "a/", pathHistory = listOf("", "a/")))

            nav.saveScroll(12, 34)

            assertEquals(12, nav.state.scrollIndex)
            assertEquals(34, nav.state.scrollOffset)
            assertEquals("b" to nav.state, saver.calls.single())
        }

    @Test
    fun `push after restoring scroll resets position to top`() =
        runTest {
            val (_, nav) = newPair()
            nav.attach("b", NavState(currentPrefix = "", pathHistory = listOf(""), scrollIndex = 9, scrollOffset = 9))

            nav.push("a/")

            assertEquals(0, nav.state.scrollIndex)
            assertEquals(0, nav.state.scrollOffset)
        }
    }
