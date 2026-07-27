package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.model.S3Account
import com.imaviso.stash.data.model.S3Bucket
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.util.ErrorUtils
import com.imaviso.stash.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class BucketSortOption(
    val displayName: String,
) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
}

/**
 * Lightweight stats for a single bucket. Fetched on demand via a recursive
 * listObjects call, so the buckets screen doesn't issue N calls on load.
 */
data class BucketStats(
    val fileCount: Int,
    val totalSize: Long,
    val folderCount: Int,
) {
    val formattedSize: String
        get() =
            when {
                totalSize < 1024 -> "$totalSize B"
                totalSize < 1024 * 1024 -> "%.1f KB".format(totalSize / 1024f)
                totalSize < 1024 * 1024 * 1024 -> "%.1f MB".format(totalSize / (1024f * 1024f))
                else -> "%.2f GB".format(totalSize / (1024f * 1024f * 1024f))
            }
}

data class BucketsUiState(
    val buckets: List<S3Bucket> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false,
    val isConfigured: Boolean = false,
    val showCreateDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val selectedBucket: S3Bucket? = null,
    val newBucketName: String = "",
    val isCreating: Boolean = false,
    val isDeleting: Boolean = false,
    // Account info
    val activeAccount: S3Account? = null,
    val accounts: List<S3Account> = emptyList(),
    val showAccountPicker: Boolean = false,
    // Bucket sort
    val sortOption: BucketSortOption = BucketSortOption.NAME_ASC,
    // Bucket stats dialog (opt-in)
    val showStatsDialog: Boolean = false,
    val statsBucket: S3Bucket? = null,
    val bucketStats: BucketStats? = null,
    val isLoadingStats: Boolean = false,
)

class BucketsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val configRepository = ConfigRepository(application)
    private val s3Service = S3Service()

    private val _uiState = MutableStateFlow(BucketsUiState())
    val uiState: StateFlow<BucketsUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
        checkConfigAndLoad()
        observeNetworkConnectivity()
    }

    private fun observeNetworkConnectivity() {
        viewModelScope.launch {
            NetworkUtils.observeNetworkConnectivity(context).collect { isConnected ->
                val wasOffline = _uiState.value.isOffline
                _uiState.value = _uiState.value.copy(isOffline = !isConnected)
                // Auto-refresh when coming back online
                if (isConnected && wasOffline && _uiState.value.isConfigured) {
                    refresh()
                }
            }
        }
    }

    private fun checkNetwork(): Boolean {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            _uiState.value =
                _uiState.value.copy(
                    isOffline = true,
                    error = "No internet connection. Please check your network and try again.",
                )
            return false
        }
        return true
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            configRepository.accountsFlow.collect { accounts ->
                _uiState.value = _uiState.value.copy(accounts = accounts)
            }
        }
        viewModelScope.launch {
            configRepository.activeAccountFlow.collect { account ->
                val previousAccount = _uiState.value.activeAccount
                _uiState.value = _uiState.value.copy(activeAccount = account)

                // Reload buckets if account changed
                if (previousAccount?.id != account?.id && account != null) {
                    initializeAndLoadBuckets(account.toConfig())
                }
            }
        }
    }

    private fun checkConfigAndLoad() {
        viewModelScope.launch {
            val config = configRepository.configFlow.first()
            if (config.isValid()) {
                _uiState.value = _uiState.value.copy(isConfigured = true)
                initializeAndLoadBuckets(config)
            } else {
                _uiState.value =
                    _uiState.value.copy(
                        isConfigured = false,
                        error = null, // Don't show error, just show "not configured" view
                    )
            }
        }
    }

    private suspend fun initializeAndLoadBuckets(config: S3Config) {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    isOffline = true,
                    error = "No internet connection. Please check your network and try again.",
                )
            return
        }

        _uiState.value =
            _uiState.value.copy(
                isLoading = true,
                error = null,
                isConfigured = true,
                isOffline = false,
            )

        try {
            s3Service.initialize(config)
            loadBuckets()
        } catch (e: Exception) {
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    error = ErrorUtils.formatError(e),
                )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!checkNetwork()) return@launch
            val config = configRepository.configFlow.first()
            if (config.isValid()) {
                initializeAndLoadBuckets(config)
            }
        }
    }

    private suspend fun loadBuckets() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, isOffline = false)

        s3Service
            .listBuckets()
            .onSuccess { buckets ->
                _uiState.value =
                    _uiState.value.copy(
                        buckets = buckets,
                        isLoading = false,
                    )
            }.onFailure { e ->
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = ErrorUtils.formatError(e),
                    )
            }
    }

    // ==================== ACCOUNT SWITCHING ====================

    fun showAccountPicker() {
        _uiState.value = _uiState.value.copy(showAccountPicker = true)
    }

    fun hideAccountPicker() {
        _uiState.value = _uiState.value.copy(showAccountPicker = false)
    }

    fun switchAccount(account: S3Account) {
        viewModelScope.launch {
            configRepository.setActiveAccount(account.id)
            _uiState.value = _uiState.value.copy(showAccountPicker = false)
        }
    }

    // ==================== BUCKET OPERATIONS ====================

    fun showCreateDialog() {
        _uiState.value = _uiState.value.copy(showCreateDialog = true, newBucketName = "")
    }

    fun hideCreateDialog() {
        _uiState.value = _uiState.value.copy(showCreateDialog = false, newBucketName = "")
    }

    fun updateNewBucketName(name: String) {
        _uiState.value = _uiState.value.copy(newBucketName = name)
    }

    fun createBucket() {
        val name = _uiState.value.newBucketName.trim()
        if (name.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true)

            s3Service
                .createBucket(name)
                .onSuccess {
                    _uiState.value =
                        _uiState.value.copy(
                            isCreating = false,
                            showCreateDialog = false,
                            newBucketName = "",
                        )
                    loadBuckets()
                }.onFailure { e ->
                    _uiState.value =
                        _uiState.value.copy(
                            isCreating = false,
                            error = ErrorUtils.formatError(e),
                        )
                }
        }
    }

    fun showDeleteDialog(bucket: S3Bucket) {
        _uiState.value =
            _uiState.value.copy(
                showDeleteDialog = true,
                selectedBucket = bucket,
            )
    }

    fun hideDeleteDialog() {
        _uiState.value =
            _uiState.value.copy(
                showDeleteDialog = false,
                selectedBucket = null,
            )
    }

    fun deleteBucket() {
        val bucket = _uiState.value.selectedBucket ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)

            s3Service
                .deleteBucket(bucket.name)
                .onSuccess {
                    _uiState.value =
                        _uiState.value.copy(
                            isDeleting = false,
                            showDeleteDialog = false,
                            selectedBucket = null,
                        )
                    loadBuckets()
                }.onFailure { e ->
                    _uiState.value =
                        _uiState.value.copy(
                            isDeleting = false,
                            error = ErrorUtils.formatError(e),
                        )
                }
        }
    }

    // ==================== BUCKET SORTING ====================

    fun setSortOption(option: BucketSortOption) {
        _uiState.value = _uiState.value.copy(sortOption = option)
    }

    /**
     * Sort the current bucket list by the active sort option. Folders are not
     * special-cased at the bucket level.
     */
    fun sortBuckets(buckets: List<S3Bucket>): List<S3Bucket> =
        when (_uiState.value.sortOption) {
            BucketSortOption.NAME_ASC -> buckets.sortedBy { it.name.lowercase() }
            BucketSortOption.NAME_DESC -> buckets.sortedByDescending { it.name.lowercase() }
            BucketSortOption.DATE_DESC -> buckets.sortedByDescending { it.creationDate }
            BucketSortOption.DATE_ASC -> buckets.sortedBy { it.creationDate }
        }

    // ==================== BUCKET STATS ====================

    fun showBucketStats(bucket: S3Bucket) {
        _uiState.value =
            _uiState.value.copy(
                showStatsDialog = true,
                statsBucket = bucket,
                bucketStats = null,
                isLoadingStats = true,
            )
        loadBucketStats(bucket)
    }

    fun hideBucketStats() {
        _uiState.value =
            _uiState.value.copy(
                showStatsDialog = false,
                statsBucket = null,
                bucketStats = null,
                isLoadingStats = false,
            )
    }

    private fun loadBucketStats(bucket: S3Bucket) {
        viewModelScope.launch {
            s3Service
                .listObjectsRecursive(bucket.name, prefix = "")
                .onSuccess { objects ->
                    val files = objects.filter { !it.key.endsWith("/") }
                    val folders = objects.filter { it.key.endsWith("/") }
                    val stats =
                        BucketStats(
                            fileCount = files.size,
                            totalSize = files.sumOf { it.size },
                            folderCount = folders.size,
                        )
                    _uiState.value =
                        _uiState.value.copy(
                            bucketStats = stats,
                            isLoadingStats = false,
                        )
                }.onFailure { e ->
                    _uiState.value =
                        _uiState.value.copy(
                            isLoadingStats = false,
                            error = "Failed to load stats: ${e.message}",
                        )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        s3Service.close()
    }
}
