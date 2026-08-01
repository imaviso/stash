package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.imaviso.stash.data.transfer.TransferInfo
import com.imaviso.stash.data.transfer.TransferManager
import com.imaviso.stash.data.transfer.TransferState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/**
 * Read-only view model for the Transfers screen. Backed by the transfer
 * module (single authority) so it shows active + history regardless of which
 * screen initiated the transfers; cancellation delegates to the module's
 * dispatch (background work or in-process job).
 */
class TransfersViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val transferManager = TransferManager.getInstance(application)

    val activeTransfers: StateFlow<List<TransferInfo>> =
        transferManager.activeTransfers
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyTransfers: StateFlow<List<TransferInfo>> =
        transferManager.historyTransfers
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancelTransfer(transferId: String) {
        transferManager.cancel(transferId)
    }

    fun cancelAll() {
        transferManager.records.value.values
            .filter { it.state == TransferState.ACTIVE }
            .forEach { transferManager.cancel(it.id) }
    }

    fun clearHistory() {
        transferManager.clearHistory()
    }
}
