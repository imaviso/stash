package com.imaviso.stash.data.model

import java.net.URLEncoder

/**
 * Object key value type — single owner of the object-key grammar:
 * '/'-separated segments, trailing '/' = folder, "" = bucket root.
 *
 * Keys are bucket-relative, so leading '/' is trimmed on construction and a
 * key never ends with '/' unless folder semantics are explicit in the raw
 * string. Pure Kotlin (no Android imports) so it is JVM-testable.
 */
class ObjectKey private constructor(
    val key: String,
) {
    /** Last segment; a folder's trailing '/' is stripped first. */
    val fileName: String
        get() = key.trimEnd('/').substringAfterLast('/')

    /** Folder semantics: the key ends with '/'. */
    val isFolder: Boolean
        get() = key.endsWith('/')

    /**
     * Parent prefix of this key, or null at the bucket root ("").
     * Parents are folder keys: "a/b/c.jpg" -> "a/", "a/b/" -> "a/", "b.jpg" -> "".
     */
    fun parent(): ObjectKey? {
        val base = key.trimEnd('/')
        if (base.isEmpty()) return null
        if ('/' !in base) return ObjectKey("")
        return ObjectKey(base.substringBeforeLast('/')).asFolder()
    }

    /**
     * Join [name] onto this key. A name is a single segment: it is trimmed and
     * '/' is stripped (same sanitize rule as the share-upload folder creation).
     * Names append directly to folder keys, otherwise a '/' separator is added.
     */
    fun child(name: String): ObjectKey {
        val segment = name.trim().replace("/", "")
        return when {
            key.isEmpty() -> ObjectKey(segment)
            isFolder -> ObjectKey(key + segment)
            else -> ObjectKey("$key/$segment")
        }
    }

    /** Ensure folder semantics (trailing '/'). The root "" has no folder form. */
    fun asFolder(): ObjectKey =
        when {
            key.isEmpty() || isFolder -> this
            else -> ObjectKey("$key/")
        }

    /**
     * Per-segment URL encoding keeping '/' separators intact — the same rules
     * as the S3 CopyObject copySource encoding (URLEncoder, '+' -> "%20").
     */
    val encoded: String
        get() =
            key.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }

    /**
     * Name relative to [currentPrefix], with a folder's trailing '/' trimmed
     * for display. Keys outside the prefix display as their full key.
     */
    fun displayName(currentPrefix: String): String {
        val relative =
            if (currentPrefix.isNotEmpty() && key.startsWith(currentPrefix)) {
                key.removePrefix(currentPrefix)
            } else {
                key
            }
        return relative.trimEnd('/')
    }

    override fun equals(other: Any?): Boolean = other is ObjectKey && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    companion object {
        operator fun invoke(raw: String): ObjectKey {
            val trimmed = raw.trimStart('/')
            // Collapse a run of trailing slashes into the single folder marker.
            val normalized = if (trimmed.endsWith('/')) trimmed.trimEnd('/') + "/" else trimmed
            return ObjectKey(normalized)
        }
    }
}
