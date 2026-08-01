package com.imaviso.stash.ui.components

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Open [file] with an external app via ACTION_VIEW chooser.
 * Returns null on success or a failure message.
 */
fun openFileWithExternalApp(
    context: Context,
    file: File,
    mimeType: String,
): String? {
    return try {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        val chooser =
            Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(chooser)
        null
    } catch (e: Exception) {
        "Failed to open file: ${e.message}"
    }
}
