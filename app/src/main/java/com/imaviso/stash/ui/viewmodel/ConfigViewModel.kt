package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.model.S3Account
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.util.ErrorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigUiState(
    val config: S3Config = S3Config(),
    val accounts: List<S3Account> = emptyList(),
    val activeAccountId: String? = null,
    val editingAccount: S3Account? = null,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val testSuccess: Boolean = false,
    val message: String? = null,
    val isSuccess: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val accountToDelete: S3Account? = null,
    val appLockEnabled: Boolean = false,
)

class ConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = ConfigRepository.getInstance(application)
    private val s3Service = S3Service.getInstance()

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
        viewModelScope.launch {
            repository.accountsFlow.collect { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
        viewModelScope.launch {
            repository.activeAccountIdFlow.collect { activeId ->
                _uiState.update { it.copy(activeAccountId = activeId) }
            }
        }
        viewModelScope.launch {
            repository.appLockEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(appLockEnabled = enabled) }
            }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAppLockEnabled(enabled)
        }
    }

    // ==================== ACCOUNT MANAGEMENT ====================

    fun startNewAccount() {
        _uiState.update { it.copy(
                editingAccount = S3Account(),
                isEditing = true,
            ) }
    }

    fun editAccount(account: S3Account) {
        _uiState.update { it.copy(
                editingAccount = account,
                isEditing = true,
            ) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(
                editingAccount = null,
                isEditing = false,
            ) }
    }

    fun updateEditingAccountName(name: String) {
        _uiState.update { it.copy(
                editingAccount = _uiState.value.editingAccount?.copy(name = name),
            ) }
    }

    fun updateEditingAccountEndpoint(endpoint: String) {
        _uiState.update { it.copy(
                editingAccount = _uiState.value.editingAccount?.copy(endpoint = endpoint),
            ) }
    }

    fun updateEditingAccountAccessKey(accessKey: String) {
        _uiState.update { it.copy(
                editingAccount = _uiState.value.editingAccount?.copy(accessKey = accessKey),
            ) }
    }

    fun updateEditingAccountSecretKey(secretKey: String) {
        _uiState.update { it.copy(
                editingAccount = _uiState.value.editingAccount?.copy(secretKey = secretKey),
            ) }
    }

    fun updateEditingAccountRegion(region: String) {
        _uiState.update { it.copy(
                editingAccount = _uiState.value.editingAccount?.copy(region = region),
            ) }
    }

    fun updateEditingAccountUsePathStyle(usePathStyle: Boolean) {
        _uiState.update { it.copy(
                editingAccount = _uiState.value.editingAccount?.copy(usePathStyle = usePathStyle),
            ) }
    }

    fun testConnection() {
        val account = _uiState.value.editingAccount ?: return
        if (!account.isValid()) {
            _uiState.update { it.copy(
                    testResult = "Please fill in all required fields first",
                    testSuccess = false,
                ) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(
                    isTesting = true,
                    testResult = null,
                ) }

            val config = account.toConfig()
            s3Service
                .testConnection(config)
                .onSuccess { message ->
                    _uiState.update { it.copy(
                            isTesting = false,
                            testResult = message,
                            testSuccess = true,
                        ) }
                }.onFailure { e ->
                    _uiState.update { it.copy(
                        isTesting = false,
                        testResult = ErrorUtils.formatError(e),
                        testSuccess = false,
                    ) }
                }
        }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null) }
    }

    fun saveEditingAccount() {
        val account = _uiState.value.editingAccount ?: return
        if (!account.isValid()) {
            _uiState.update { it.copy(
                    message = "Please fill in all required fields",
                    isSuccess = false,
                ) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                repository.saveAccount(account)
                _uiState.update { it.copy(
                        isSaving = false,
                        isEditing = false,
                        editingAccount = null,
                        message = "Account saved successfully",
                        isSuccess = true,
                    ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                        isSaving = false,
                        message = "Failed to save: ${e.message}",
                        isSuccess = false,
                    ) }
            }
        }
    }

    fun showDeleteAccountDialog(account: S3Account) {
        _uiState.update { it.copy(
                showDeleteDialog = true,
                accountToDelete = account,
            ) }
    }

    fun hideDeleteAccountDialog() {
        _uiState.update { it.copy(
                showDeleteDialog = false,
                accountToDelete = null,
            ) }
    }

    fun deleteAccount() {
        val account = _uiState.value.accountToDelete ?: return
        viewModelScope.launch {
            try {
                repository.deleteAccount(account.id)
                _uiState.update { it.copy(
                        showDeleteDialog = false,
                        accountToDelete = null,
                        message = "Account deleted",
                        isSuccess = true,
                    ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                        message = "Failed to delete: ${e.message}",
                        isSuccess = false,
                    ) }
            }
        }
    }

    fun setActiveAccount(account: S3Account) {
        viewModelScope.launch {
            repository.setActiveAccount(account.id)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
