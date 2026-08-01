package com.imaviso.stash.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ThumbnailCache] TTL + invalidation, driven with an injected fake clock.
 */
class ThumbnailCacheTest {
    private class FakeClock(
        var now: Long = 0L,
    ) {
        fun millis(): Long = now
    }

    @Test
    fun `miss on empty cache`() {
        val cache = ThumbnailCache(now = { 0L })
        assertNull(cache.get("bucket", "a.jpg"))
    }

    @Test
    fun `put then get within the TTL hits`() {
        val clock = FakeClock()
        val cache = ThumbnailCache(ttlMillis = 1000, now = clock::millis)
        cache.put("bucket", "a.jpg", "https://url")

        clock.now = 999
        assertEquals("https://url", cache.get("bucket", "a.jpg"))
    }

    @Test
    fun `entry expires exactly at the TTL boundary`() {
        val clock = FakeClock()
        val cache = ThumbnailCache(ttlMillis = 1000, now = clock::millis)
        cache.put("bucket", "a.jpg", "https://url")

        clock.now = 1000
        assertNull(cache.get("bucket", "a.jpg"))
    }

    @Test
    fun `expired entry is refreshed by a new put`() {
        val clock = FakeClock()
        val cache = ThumbnailCache(ttlMillis = 1000, now = clock::millis)
        cache.put("bucket", "a.jpg", "https://old")
        clock.now = 2000

        cache.put("bucket", "a.jpg", "https://new")

        assertEquals("https://new", cache.get("bucket", "a.jpg"))
    }

    @Test
    fun `clear invalidates everything - account switch path`() {
        val clock = FakeClock()
        val cache = ThumbnailCache(ttlMillis = 1000, now = clock::millis)
        cache.put("bucket", "a.jpg", "https://a")
        cache.put("bucket", "b.jpg", "https://b")

        cache.clear()

        assertNull(cache.get("bucket", "a.jpg"))
        assertNull(cache.get("bucket", "b.jpg"))
    }

    @Test
    fun `cache is keyed by bucket and key`() {
        val cache = ThumbnailCache(now = { 0L })
        cache.put("bucket-a", "same.jpg", "https://a")
        cache.put("bucket-b", "same.jpg", "https://b")

        assertEquals("https://a", cache.get("bucket-a", "same.jpg"))
        assertEquals("https://b", cache.get("bucket-b", "same.jpg"))
        assertNull(cache.get("bucket-c", "same.jpg"))
    }

    @Test
    fun `default TTL is 50 minutes`() {
        val clock = FakeClock()
        val cache = ThumbnailCache(now = clock::millis)
        cache.put("bucket", "a.jpg", "https://url")

        clock.now = 50L * 60 * 1000 - 1
        assertEquals("https://url", cache.get("bucket", "a.jpg"))
    }
}
