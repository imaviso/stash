package com.imaviso.stash.util

/**
 * Shared byte-size formatting (single source for all screens/models).
 */
object FormatUtils {
    fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
            else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
        }
}
