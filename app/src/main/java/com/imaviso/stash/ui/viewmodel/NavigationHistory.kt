package com.imaviso.stash.ui.viewmodel

/**
 * Snapshot of folder navigation: back-stack of prefixes ("" = bucket root)
 * plus scroll position. Mirrors the persisted shape
 * (ConfigRepository.BucketNavState) at the persistence seam while keeping
 * this core free of Android imports so it is JVM-testable.
 */
data class NavState(
    val currentPrefix: String = "",
    val pathHistory: List<String> = listOf(""),
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
)

/**
 * Navigation back-stack + persistence. Pure in-memory core; every mutation
 * is written through the injected [persist] adapter (ConfigRepository/
 * DataStore in production, fake in tests). Prefix grammar itself stays owned
 * by ObjectKey - these are already-normalized full prefixes.
 */
class NavigationHistory(
    private val persist: suspend (bucket: String, state: NavState) -> Unit,
) {
    private var bucket: String = ""

    var state: NavState = NavState()
        private set

    val canGoUp: Boolean get() = state.pathHistory.size > 1

    /**
     * Attach to [bucket]; [saved] (if any) replaces the in-memory state
     * wholesale - persistence wins on bucket open.
     */
    fun attach(
        bucket: String,
        saved: NavState?,
    ) {
        this.bucket = bucket
        state = saved ?: NavState()
    }

    /** Enter [prefix]; resets scroll. */
    suspend fun push(prefix: String) {
        state =
            state.copy(
                currentPrefix = prefix,
                pathHistory = state.pathHistory + prefix,
                scrollIndex = 0,
                scrollOffset = 0,
            )
        persist(bucket, state)
    }

    /** Go up one level. Returns the new state, or null when already at root. */
    suspend fun pop(): NavState? {
        if (!canGoUp) return null
        val newHistory = state.pathHistory.dropLast(1)
        state =
            state.copy(
                currentPrefix = newHistory.last(),
                pathHistory = newHistory,
                scrollIndex = 0,
                scrollOffset = 0,
            )
        persist(bucket, state)
        return state
    }

    /** Jump to back-stack [index] (breadcrumb). Returns null when out of range. */
    suspend fun jumpTo(index: Int): NavState? {
        if (index < 0 || index >= state.pathHistory.size) return null
        val newHistory = state.pathHistory.take(index + 1)
        state =
            state.copy(
                currentPrefix = newHistory.last(),
                pathHistory = newHistory,
                scrollIndex = 0,
                scrollOffset = 0,
            )
        persist(bucket, state)
        return state
    }

    /** Record + persist scroll position for the current prefix. */
    suspend fun saveScroll(
        index: Int,
        offset: Int,
    ) {
        state = state.copy(scrollIndex = index, scrollOffset = offset)
        persist(bucket, state)
    }
}
