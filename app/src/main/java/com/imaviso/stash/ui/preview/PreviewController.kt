package com.imaviso.stash.ui.preview

import com.imaviso.stash.data.model.FileType
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.remote.S3Operations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Per-item preview resolution: how the screen should source the current
 * object. Screens render this thinly and make no policy decisions.
 */
sealed interface PreviewSource {
    /** Stream from a presigned URL (video/audio/image) - no in-RAM copy. */
    data class Stream(
        val url: String,
        val mimeType: String,
    ) : PreviewSource

    /** Whole object in memory (text/PDF/small files under the cap). */
    data class Bytes(
        val data: ByteArray,
        val mimeType: String,
    ) : PreviewSource {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && mimeType == other.mimeType && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * data.contentHashCode() + mimeType.hashCode()
    }

    /** Exceeds the in-memory cap; download instead of in-app preview. */
    data class TooLarge(
        val sizeBytes: Long,
    ) : PreviewSource

    /** No preview exists for this target (e.g. a folder). */
    data object Unsupported : PreviewSource

    data object Loading : PreviewSource

    data class Error(
        val message: String,
    ) : PreviewSource
}

/**
 * State of one swipe-preview session: the file list snapshot, the current
 * index into it, and the resolved source for the current object.
 */
data class PreviewUiState(
    val objects: List<S3Object> = emptyList(),
    val index: Int = 0,
    val source: PreviewSource = PreviewSource.Loading,
) {
    val currentObject: S3Object? get() = objects.getOrNull(index)
}

/**
 * Pure preview policy: which file types stream vs load whole bytes, and the
 * in-memory size cap. Context-free and JVM-testable. Streaming types are
 * exempt from the cap (streaming is the anti-OOM path); everything else
 * (text/PDF/other) loads bytes below the cap, matching the screen's existing
 * behavior for non-previewable file types (renders Unsupported, bytes still
 * allow "Open with").
 */
internal object PreviewPolicy {
    /** Above this many bytes an object is never held fully in RAM. */
    const val MAX_IN_MEMORY_BYTES: Long = 10L * 1024 * 1024

    enum class Route {
        STREAM,
        BYTES,
        TOO_LARGE,
        UNSUPPORTED,
    }

    fun routeFor(
        fileType: FileType,
        sizeBytes: Long,
        isFolder: Boolean = false,
    ): Route =
        when {
            isFolder -> Route.UNSUPPORTED
            fileType == FileType.IMAGE || fileType == FileType.VIDEO || fileType == FileType.AUDIO -> Route.STREAM
            sizeBytes > MAX_IN_MEMORY_BYTES -> Route.TOO_LARGE
            else -> Route.BYTES
        }
}

/**
 * Preview module: owns preview POLICY (stream-vs-bytes, 10MB in-memory cap,
 * type routing) and per-item source resolution for one swipe session
 * (snapshot of the previewable files at open time). Presigned URLs are
 * generated here for streams; bytes go through the [S3Operations] port.
 *
 * Lifecycle is the session: the caller (ViewModel) creates an instance on
 * open, drops it on close. Only the latest selection's load can emit.
 */
class PreviewController(
    private val s3: S3Operations,
    private val bucketName: String,
    objects: List<S3Object>,
    initialIndex: Int = 0,
    private val scope: CoroutineScope,
) {
    private val _state =
        MutableStateFlow(
            PreviewUiState(
                objects = objects,
                index = initialIndex.coerceIn(0, (objects.size - 1).coerceAtLeast(0)),
            ),
        )
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadCurrent()
    }

    /** Navigate the session to [index] and resolve the source for it. */
    fun select(index: Int) {
        if (index < 0 || index >= _state.value.objects.size) return
        loadJob?.cancel()
        _state.value = _state.value.copy(index = index, source = PreviewSource.Loading)
        loadCurrent()
    }

    private fun loadCurrent() {
        val obj = _state.value.currentObject ?: return
        when (PreviewPolicy.routeFor(obj.fileType, obj.size, obj.isFolder)) {
            PreviewPolicy.Route.UNSUPPORTED ->
                _state.value = _state.value.copy(source = PreviewSource.Unsupported)

            PreviewPolicy.Route.TOO_LARGE ->
                _state.value = _state.value.copy(source = PreviewSource.TooLarge(obj.size))

            PreviewPolicy.Route.STREAM ->
                loadJob =
                    scope.launch {
                        s3
                            .getPresignedUrl(bucketName, obj.key)
                            .onSuccess { url ->
                                _state.value = _state.value.copy(source = PreviewSource.Stream(url, obj.mimeType))
                            }.onFailure { e ->
                                _state.value =
                                    _state.value.copy(
                                        source = PreviewSource.Error(e.message ?: "Failed to get stream URL"),
                                    )
                            }
                    }

            PreviewPolicy.Route.BYTES ->
                loadJob =
                    scope.launch {
                        s3
                            .downloadObject(bucketName, obj.key)
                            .onSuccess { data ->
                                _state.value = _state.value.copy(source = PreviewSource.Bytes(data, obj.mimeType))
                            }.onFailure { e ->
                                _state.value =
                                    _state.value.copy(
                                        source = PreviewSource.Error(e.message ?: "Failed to load file"),
                                    )
                            }
                    }
        }
    }
}
