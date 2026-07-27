package com.imaviso.stash.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Direction of a transfer.
 */
enum class TransferType {
    UPLOAD,
    DOWNLOAD,
}

/**
 * Lifecycle state of a transfer.
 * ACTIVE: in progress. Terminal states are kept in history until cleared.
 */
enum class TransferState {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * A single transfer (upload or download) tracked across the app.
 * Active transfers live in [TransferRepository.transfers] with state ACTIVE;
 * terminal records remain for history until cleared.
 */
data class TransferInfo(
    val id: String,
    val fileName: String,
    val type: TransferType,
    val progress: Int = 0,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val status: String = "",
    val error: String? = null,
    val state: TransferState = TransferState.ACTIVE,
    val timestamp: Long = System.currentTimeMillis(),
    val bucketName: String = "",
)

/**
 * App-wide sink for transfer events. Survives per-screen ViewModel destruction
 * so the Transfers screen can show active + completed history regardless of
 * which bucket screen initiated the transfer.
 */
object TransferRepository {
    private val _transfers = MutableStateFlow<List<TransferInfo>>(emptyList())
    val transfers: StateFlow<List<TransferInfo>> = _transfers.asStateFlow()

    val activeTransfers: List<TransferInfo>
        get() = _transfers.value.filter { it.state == TransferState.ACTIVE }

    fun upsert(transfer: TransferInfo) {
        _transfers.update { list ->
            val idx = list.indexOfFirst { it.id == transfer.id }
            if (idx >= 0) list.toMutableList().apply { this[idx] = transfer } else list + transfer
        }
    }

    /**
     * Mark an existing active transfer as terminal. If the id is unknown, the
     * record is inserted so the terminal event is not lost.
     */
    fun markTerminal(
        id: String,
        state: TransferState,
        fileName: String,
        type: TransferType,
        bucketName: String,
        error: String? = null,
        totalBytes: Long = 0,
        bytesTransferred: Long = 0,
    ) {
        val now = System.currentTimeMillis()
        _transfers.update { list ->
            val idx = list.indexOfFirst { it.id == id }
            val terminal =
                if (idx >= 0) {
                    list[idx].copy(
                        state = state,
                        error = error,
                        timestamp = now,
                        progress = if (state == TransferState.COMPLETED) 100 else list[idx].progress,
                        bytesTransferred = if (bytesTransferred > 0) bytesTransferred else list[idx].bytesTransferred,
                        totalBytes = if (totalBytes > 0) totalBytes else list[idx].totalBytes,
                        status = state.name.lowercase().replaceFirstChar { it.uppercase() },
                    )
                } else {
                    TransferInfo(
                        id = id,
                        fileName = fileName,
                        type = type,
                        state = state,
                        error = error,
                        timestamp = now,
                        totalBytes = totalBytes,
                        bytesTransferred = bytesTransferred,
                        status = state.name.lowercase().replaceFirstChar { it.uppercase() },
                        bucketName = bucketName,
                    )
                }
            if (idx >= 0) list.toMutableList().apply { this[idx] = terminal } else list + terminal
        }
    }

    /**
     * Drop an active record without producing a history entry (e.g. cancelled
     * before the worker observed a terminal state).
     */
    fun removeActive(id: String) {
        _transfers.update { list -> list.filterNot { it.id == id && it.state == TransferState.ACTIVE } }
    }

    /**
     * Remove all terminal records, keeping only currently active transfers.
     */
    fun clearHistory() {
        _transfers.update { list -> list.filter { it.state == TransferState.ACTIVE } }
    }
}
