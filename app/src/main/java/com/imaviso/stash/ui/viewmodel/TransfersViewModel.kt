package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.work.WorkManager
import com.imaviso.stash.data.repository.TransferInfo
import com.imaviso.stash.data.repository.TransferRepository
import com.imaviso.stash.data.repository.TransferState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/**
 * Read-only view model for the Transfers screen. Backed by the app-wide
 * [TransferRepository] so it shows active + history regardless of which
 * bucket screen initiated the transfers.
 */
class TransfersViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)

    val activeTransfers: StateFlow<List<TransferInfo>> =
        TransferRepository.transfers
            .map { list -> list.filter { it.state == TransferState.ACTIVE } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyTransfers: StateFlow<List<TransferInfo>> =
        TransferRepository.transfers
            .map { list -> list.filter { it.state != TransferState.ACTIVE }.sortedByDescending { it.timestamp } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun cancelTransfer(transferId: String) {
        workManager.cancelAllWorkByTag("transfer_$transferId")
        val active = TransferRepository.transfers.value.firstOrNull { it.id == transferId } ?: return
        TransferRepository.markTerminal(
            id = transferId,
            state = TransferState.CANCELLED,
            fileName = active.fileName,
            type = active.type,
            bucketName = active.bucketName,
        )
    }

    fun cancelAll() {
        TransferRepository.transfers.value
            .filter { it.state == TransferState.ACTIVE }
            .forEach {
                workManager.cancelAllWorkByTag("transfer_${it.id}")
                TransferRepository.markTerminal(
                    id = it.id,
                    state = TransferState.CANCELLED,
                    fileName = it.fileName,
                    type = it.type,
                    bucketName = it.bucketName,
                )
            }
    }

    fun clearHistory() {
        TransferRepository.clearHistory()
    }
}
