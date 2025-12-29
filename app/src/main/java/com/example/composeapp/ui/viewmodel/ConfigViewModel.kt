package com.example.composeapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.composeapp.data.model.S3Account
import com.example.composeapp.data.model.S3Config
import com.example.composeapp.data.repository.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConfigUiState(
    val config: S3Config = S3Config(),
    val accounts: List<S3Account> = emptyList(),
    val activeAccountId: String? = null,
    val editingAccount: S3Account? = null,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isSuccess: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val accountToDelete: S3Account? = null
)

class ConfigViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = ConfigRepository(application)
    
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()
    
    init {
        loadData()
    }
    
    private fun loadData() {
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _uiState.value = _uiState.value.copy(config = config)
            }
        }
        viewModelScope.launch {
            repository.accountsFlow.collect { accounts ->
                _uiState.value = _uiState.value.copy(accounts = accounts)
            }
        }
        viewModelScope.launch {
            repository.activeAccountIdFlow.collect { activeId ->
                _uiState.value = _uiState.value.copy(activeAccountId = activeId)
            }
        }
    }
    
    // ==================== ACCOUNT MANAGEMENT ====================
    
    fun startNewAccount() {
        _uiState.value = _uiState.value.copy(
            editingAccount = S3Account(),
            isEditing = true
        )
    }
    
    fun editAccount(account: S3Account) {
        _uiState.value = _uiState.value.copy(
            editingAccount = account,
            isEditing = true
        )
    }
    
    fun cancelEditing() {
        _uiState.value = _uiState.value.copy(
            editingAccount = null,
            isEditing = false
        )
    }
    
    fun updateEditingAccountName(name: String) {
        _uiState.value = _uiState.value.copy(
            editingAccount = _uiState.value.editingAccount?.copy(name = name)
        )
    }
    
    fun updateEditingAccountEndpoint(endpoint: String) {
        _uiState.value = _uiState.value.copy(
            editingAccount = _uiState.value.editingAccount?.copy(endpoint = endpoint)
        )
    }
    
    fun updateEditingAccountAccessKey(accessKey: String) {
        _uiState.value = _uiState.value.copy(
            editingAccount = _uiState.value.editingAccount?.copy(accessKey = accessKey)
        )
    }
    
    fun updateEditingAccountSecretKey(secretKey: String) {
        _uiState.value = _uiState.value.copy(
            editingAccount = _uiState.value.editingAccount?.copy(secretKey = secretKey)
        )
    }
    
    fun updateEditingAccountRegion(region: String) {
        _uiState.value = _uiState.value.copy(
            editingAccount = _uiState.value.editingAccount?.copy(region = region)
        )
    }
    
    fun updateEditingAccountUsePathStyle(usePathStyle: Boolean) {
        _uiState.value = _uiState.value.copy(
            editingAccount = _uiState.value.editingAccount?.copy(usePathStyle = usePathStyle)
        )
    }
    
    fun saveEditingAccount() {
        val account = _uiState.value.editingAccount ?: return
        if (!account.isValid()) {
            _uiState.value = _uiState.value.copy(
                message = "Please fill in all required fields",
                isSuccess = false
            )
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                repository.saveAccount(account)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isEditing = false,
                    editingAccount = null,
                    message = "Account saved successfully",
                    isSuccess = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "Failed to save: ${e.message}",
                    isSuccess = false
                )
            }
        }
    }
    
    fun showDeleteAccountDialog(account: S3Account) {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = true,
            accountToDelete = account
        )
    }
    
    fun hideDeleteAccountDialog() {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = false,
            accountToDelete = null
        )
    }
    
    fun deleteAccount() {
        val account = _uiState.value.accountToDelete ?: return
        viewModelScope.launch {
            try {
                repository.deleteAccount(account.id)
                _uiState.value = _uiState.value.copy(
                    showDeleteDialog = false,
                    accountToDelete = null,
                    message = "Account deleted",
                    isSuccess = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    message = "Failed to delete: ${e.message}",
                    isSuccess = false
                )
            }
        }
    }
    
    fun setActiveAccount(account: S3Account) {
        viewModelScope.launch {
            repository.setActiveAccount(account.id)
        }
    }
    
    // ==================== LEGACY SINGLE CONFIG ====================
    
    fun updateEndpoint(endpoint: String) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config.copy(endpoint = endpoint)
        )
    }
    
    fun updateAccessKey(accessKey: String) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config.copy(accessKey = accessKey)
        )
    }
    
    fun updateSecretKey(secretKey: String) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config.copy(secretKey = secretKey)
        )
    }
    
    fun updateRegion(region: String) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config.copy(region = region)
        )
    }
    
    fun updateUsePathStyle(usePathStyle: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config.copy(usePathStyle = usePathStyle)
        )
    }
    
    fun saveConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                repository.saveConfig(_uiState.value.config)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "Configuration saved successfully",
                    isSuccess = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "Failed to save: ${e.message}",
                    isSuccess = false
                )
            }
        }
    }
    
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
