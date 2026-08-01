package com.imaviso.stash.ui.viewmodel

/**
 * TTL cache of presigned thumbnail URLs, keyed by bucket/key so buckets never
 * collide. Entries expire ahead of the 1h presign TTL so stale URLs are
 * regenerated before the server rejects them. Cleared on account switch
 * (URLs are per-account).
 *
 * Clock is injected so expiry is JVM-testable with a fake clock.
 */
class ThumbnailCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val url: String,
        val expiresAtMillis: Long,
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun get(
        bucket: String,
        key: String,
    ): String? {
        val entry = entries["$bucket/$key"] ?: return null
        return if (now() < entry.expiresAtMillis) entry.url else null
    }

    @Synchronized
    fun put(
        bucket: String,
        key: String,
        url: String,
    ) {
        entries["$bucket/$key"] = Entry(url, now() + ttlMillis)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 50L * 60 * 1000 // 50 min (presign TTL is 1h)
    }
}
