package com.imaviso.stash.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Shared helper for saving files to the public Downloads directory.
 * Uses MediaStore on Android 10+ (scoped storage) and direct file access below.
 * Shared by workers and ViewModels so all saves take the same working path.
 */
object DownloadsSaver {
    /**
     * Save [sourceFile] to Downloads as [fileName]. When [relativeSubDir] is set,
     * the file lands in Downloads/<relativeSubDir>/ (subdirectories preserved).
     * Returns a content URI (API 29+) or absolute path (below) of the saved file.
     */
    fun saveToDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        relativeSubDir: String? = null,
    ): String =
        saveToDownloads(context, fileName, mimeType, relativeSubDir) { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }

    /**
     * Save in-memory [data] to Downloads as [fileName].
     * Returns a content URI (API 29+) or absolute path (below) of the saved file.
     */
    fun saveBytesToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): String =
        saveToDownloads(context, fileName, mimeType, null) { output ->
            output.write(data)
        }

    private fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        relativeSubDir: String?,
        writeTo: (java.io.OutputStream) -> Unit,
    ): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ use MediaStore
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    if (!relativeSubDir.isNullOrEmpty()) {
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$relativeSubDir")
                    }
                }

            val resolver = context.contentResolver
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { output ->
                writeTo(output)
            } ?: throw Exception("Failed to write to MediaStore")

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            uri.toString()
        } else {
            // Android 9 and below - save directly to Downloads
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destDir = if (relativeSubDir.isNullOrEmpty()) downloadsDir else File(downloadsDir, relativeSubDir)
            destDir.mkdirs()
            val destFile = File(destDir, fileName)
            java.io.FileOutputStream(destFile).use { writeTo(it) }
            destFile.absolutePath
        }
}
