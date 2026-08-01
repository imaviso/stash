package com.imaviso.stash.data

import com.imaviso.stash.data.remote.S3Operations
import com.imaviso.stash.data.remote.S3Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped owner of undo-able single-file deletes. The commit timer
 * lives on a process-wide scope, NOT a ViewModel scope: navigating away while
 * the undo snackbar shows must not cancel the commit - the snackbar said
 * "Deleted"; leaving the screen is not an undo.
 *
 * One pending delete at a time: scheduling a new one cancels the previous
 * pending commit without committing it (matches the previous
 * ViewModel-scoped semantics).
 */
class PendingDeletes(
    private val s3: S3Operations,
    private val scope: CoroutineScope,
) {
    data class PendingDelete(
        val bucket: String,
        val key: String,
    )

    /** Terminal outcome of a scheduled delete (undo emits nothing). */
    sealed interface CommitResult {
        val delete: PendingDelete

        data class Succeeded(
            override val delete: PendingDelete,
        ) : CommitResult

        data class Failed(
            override val delete: PendingDelete,
            val cause: Throwable,
        ) : CommitResult
    }

    private val _pending = MutableStateFlow<PendingDelete?>(null)
    val pending: StateFlow<PendingDelete?> = _pending.asStateFlow()

    private val _commits = MutableSharedFlow<CommitResult>(extraBufferCapacity = 4)
    val commits: SharedFlow<CommitResult> = _commits.asSharedFlow()

    private var commitJob: Job? = null

    /**
     * Schedule [key] in [bucket] for deletion after [windowMillis]. Cancels
     * any previous pending delete (its object is NOT deleted - same as
     * before).
     */
    fun schedule(
        bucket: String,
        key: String,
        windowMillis: Long = DEFAULT_WINDOW_MS,
    ) {
        commitJob?.cancel()
        val delete = PendingDelete(bucket, key)
        _pending.value = delete
        commitJob =
            scope.launch {
                delay(windowMillis)
                _pending.value = null
                s3
                    .deleteObject(delete.bucket, delete.key)
                    .fold(
                        onSuccess = { _commits.emit(CommitResult.Succeeded(delete)) },
                        onFailure = { e -> _commits.emit(CommitResult.Failed(delete, e)) },
                    )
            }
    }

    /**
     * Cancel the pending commit (undo). Returns the cancelled entry, or null
     * when nothing was pending.
     */
    fun cancelPending(): PendingDelete? {
        val current = _pending.value ?: return null
        commitJob?.cancel()
        commitJob = null
        _pending.value = null
        return current
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L

        @Volatile
        private var instance: PendingDeletes? = null

        /** Process-wide default: the commit timer survives all ViewModel scopes. */
        fun getInstance(
            s3: S3Operations = S3Service.getInstance(),
        ): PendingDeletes =
            instance ?: synchronized(this) {
                instance ?: PendingDeletes(s3, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
            }
    }
}
