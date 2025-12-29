package com.imaviso.stash.data.model

import java.util.Date

data class S3Bucket(
    val name: String,
    val creationDate: Date? = null,
)

enum class FileType {
    IMAGE,
    VIDEO,
    AUDIO,
    TEXT,
    PDF,
    OTHER,
}

data class S3Object(
    val key: String,
    val size: Long = 0,
    val lastModified: Date? = null,
    val etag: String? = null,
    val storageClass: String? = null,
) {
    val fileName: String
        get() {
            // For folders (keys ending with /), strip the trailing slash first
            val normalizedKey = key.trimEnd('/')
            return normalizedKey.substringAfterLast('/')
        }

    val isFolder: Boolean
        get() = key.endsWith('/')

    val extension: String
        get() = fileName.substringAfterLast('.', "").lowercase()

    val fileType: FileType
        get() =
            when (extension) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "heic", "heif" -> FileType.IMAGE

                "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp" -> FileType.VIDEO

                "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma" -> FileType.AUDIO

                "txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py",
                "sh", "yaml", "yml", "toml", "ini", "conf", "log", "csv",
                -> FileType.TEXT

                "pdf" -> FileType.PDF

                else -> FileType.OTHER
            }

    val mimeType: String
        get() =
            when (extension) {
                // Images
                "jpg", "jpeg" -> "image/jpeg"

                "png" -> "image/png"

                "gif" -> "image/gif"

                "webp" -> "image/webp"

                "bmp" -> "image/bmp"

                "svg" -> "image/svg+xml"

                "ico" -> "image/x-icon"

                "heic", "heif" -> "image/heic"

                // Videos
                "mp4" -> "video/mp4"

                "mkv" -> "video/x-matroska"

                "avi" -> "video/x-msvideo"

                "mov" -> "video/quicktime"

                "wmv" -> "video/x-ms-wmv"

                "flv" -> "video/x-flv"

                "webm" -> "video/webm"

                "m4v" -> "video/x-m4v"

                "3gp" -> "video/3gpp"

                // Audio
                "mp3" -> "audio/mpeg"

                "wav" -> "audio/wav"

                "ogg" -> "audio/ogg"

                "flac" -> "audio/flac"

                "aac" -> "audio/aac"

                "m4a" -> "audio/mp4"

                "wma" -> "audio/x-ms-wma"

                // Text
                "txt" -> "text/plain"

                "md" -> "text/markdown"

                "json" -> "application/json"

                "xml" -> "application/xml"

                "html" -> "text/html"

                "css" -> "text/css"

                "js" -> "text/javascript"

                "csv" -> "text/csv"

                // Other
                "pdf" -> "application/pdf"

                else -> "application/octet-stream"
            }

    val isPreviewable: Boolean
        get() = fileType in listOf(FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.TEXT, FileType.PDF)

    val formattedSize: String
        get() =
            when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
                else -> "${size / (1024 * 1024 * 1024)} GB"
            }
}

data class S3ObjectUpload(
    val key: String,
    val contentType: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as S3ObjectUpload
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()
}
