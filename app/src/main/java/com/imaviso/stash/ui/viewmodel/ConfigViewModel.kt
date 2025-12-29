package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.model.S3Account
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
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
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val testSuccess: Boolean = false,
    val message: String? = null,
    val isSuccess: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val accountToDelete: S3Account? = null,
)

class ConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = ConfigRepository(application)
    private val s3Service = S3Service()

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
        _uiState.value =
            _uiState.value.copy(
                editingAccount = S3Account(),
                isEditing = true,
            )
    }

    fun editAccount(account: S3Account) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = account,
                isEditing = true,
            )
    }

    fun cancelEditing() {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = null,
                isEditing = false,
            )
    }

    fun updateEditingAccountName(name: String) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = _uiState.value.editingAccount?.copy(name = name),
            )
    }

    fun updateEditingAccountEndpoint(endpoint: String) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = _uiState.value.editingAccount?.copy(endpoint = endpoint),
            )
    }

    fun updateEditingAccountAccessKey(accessKey: String) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = _uiState.value.editingAccount?.copy(accessKey = accessKey),
            )
    }

    fun updateEditingAccountSecretKey(secretKey: String) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = _uiState.value.editingAccount?.copy(secretKey = secretKey),
            )
    }

    fun updateEditingAccountRegion(region: String) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = _uiState.value.editingAccount?.copy(region = region),
            )
    }

    fun updateEditingAccountUsePathStyle(usePathStyle: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                editingAccount = _uiState.value.editingAccount?.copy(usePathStyle = usePathStyle),
            )
    }

    fun testConnection() {
        val account = _uiState.value.editingAccount ?: return
        if (!account.isValid()) {
            _uiState.value =
                _uiState.value.copy(
                    testResult = "Please fill in all required fields first",
                    testSuccess = false,
                )
            return
        }

        viewModelScope.launch {
            _uiState.value =
                _uiState.value.copy(
                    isTesting = true,
                    testResult = null,
                )

            val config = account.toConfig()
            s3Service
                .testConnection(config)
                .onSuccess { message ->
                    _uiState.value =
                        _uiState.value.copy(
                            isTesting = false,
                            testResult = message,
                            testSuccess = true,
                        )
                }.onFailure { e ->
                    _uiState.value =
                        _uiState.value.copy(
                            isTesting = false,
                            testResult = formatConnectionError(e),
                            testSuccess = false,
                        )
                }
        }
    }

    fun clearTestResult() {
        _uiState.value = _uiState.value.copy(testResult = null)
    }

    private fun formatConnectionError(e: Throwable): String {
        val message = e.message ?: "Unknown error"
        return when {
            message.contains("UnknownHostException") || message.contains("Unable to resolve host") -> {
                "Cannot reach server. Check the endpoint URL and your internet connection."
            }

            message.contains("ConnectException") || message.contains("Connection refused") -> {
                "Connection refused. The server may be down or the port may be wrong."
            }

            message.contains("SocketTimeoutException") || message.contains("timeout") -> {
                "Connection timed out. The server is not responding."
            }

            message.contains("InvalidAccessKeyId") || message.contains("AccessDenied") -> {
                "Invalid credentials. Check your access key and secret key."
            }

            message.contains("SignatureDoesNotMatch") -> {
                "Invalid secret key. The signature does not match."
            }

            message.contains("SSL") || message.contains("Certificate") -> {
                "SSL/TLS error. There may be a certificate issue with the server."
            }

            else -> {
                "Connection failed: $message"
            }
        }
    }

    fun saveEditingAccount() {
        val account = _uiState.value.editingAccount ?: return
        if (!account.isValid()) {
            _uiState.value =
                _uiState.value.copy(
                    message = "Please fill in all required fields",
                    isSuccess = false,
                )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                repository.saveAccount(account)
                _uiState.value =
                    _uiState.value.copy(
                        isSaving = false,
                        isEditing = false,
                        editingAccount = null,
                        message = "Account saved successfully",
                        isSuccess = true,
                    )
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isSaving = false,
                        message = "Failed to save: ${e.message}",
                        isSuccess = false,
                    )
            }
        }
    }

    fun showDeleteAccountDialog(account: S3Account) {
        _uiState.value =
            _uiState.value.copy(
                showDeleteDialog = true,
                accountToDelete = account,
            )
    }

    fun hideDeleteAccountDialog() {
        _uiState.value =
            _uiState.value.copy(
                showDeleteDialog = false,
                accountToDelete = null,
            )
    }

    fun deleteAccount() {
        val account = _uiState.value.accountToDelete ?: return
        viewModelScope.launch {
            try {
                repository.deleteAccount(account.id)
                _uiState.value =
                    _uiState.value.copy(
                        showDeleteDialog = false,
                        accountToDelete = null,
                        message = "Account deleted",
                        isSuccess = true,
                    )
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        message = "Failed to delete: ${e.message}",
                        isSuccess = false,
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
        _uiState.value =
            _uiState.value.copy(
                config = _uiState.value.config.copy(endpoint = endpoint),
            )
    }

    fun updateAccessKey(accessKey: String) {
        _uiState.value =
            _uiState.value.copy(
                config = _uiState.value.config.copy(accessKey = accessKey),
            )
    }

    fun updateSecretKey(secretKey: String) {
        _uiState.value =
            _uiState.value.copy(
                config = _uiState.value.config.copy(secretKey = secretKey),
            )
    }

    fun updateRegion(region: String) {
        _uiState.value =
            _uiState.value.copy(
                config = _uiState.value.config.copy(region = region),
            )
    }

    fun updateUsePathStyle(usePathStyle: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                config = _uiState.value.config.copy(usePathStyle = usePathStyle),
            )
    }

    fun saveConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                repository.saveConfig(_uiState.value.config)
                _uiState.value =
                    _uiState.value.copy(
                        isSaving = false,
                        message = "Configuration saved successfully",
                        isSuccess = true,
                    )
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isSaving = false,
                        message = "Failed to save: ${e.message}",
                        isSuccess = false,
                    )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
