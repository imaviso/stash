package com.imaviso.stash.data.model

import android.net.Uri

/**
 * Data class representing a file to be uploaded (shared from another app)
 */
data class SharedFileInfo(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val size: Long,
)
