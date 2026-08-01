package com.imaviso.stash.data.model

import com.imaviso.stash.util.FormatUtils

/**
 * Storage statistics for a bucket or folder
 */
data class StorageStats(
    val totalSize: Long,
    val fileCount: Int,
    val folderCount: Int,
    val byFileType: Map<FileType, FileTypeStats>,
) {
    val formattedTotalSize: String
        get() = FormatUtils.formatBytes(totalSize)
}

data class FileTypeStats(
    val count: Int,
    val totalSize: Long,
) {
    val formattedSize: String
        get() = FormatUtils.formatBytes(totalSize)
}
