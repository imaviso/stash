package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.model.ObjectKey
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.model.SharedFileInfo
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.data.transfer.TransferManager
import com.imaviso.stash.data.transfer.TransferState
import com.imaviso.stash.util.ErrorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareUploadUiState(
    val isLoading: Boolean = true,
    val hasValidConfig: Boolean = false,
    val buckets: List<String> = emptyList(),
    val selectedBucket: String? = null,
    // Folder browsing
    val currentPath: String = "",
    val pathHistory: List<String> = listOf(""),
    val folders: List<S3Object> = emptyList(),
    val isLoadingFolders: Boolean = false,
    // Create folder dialog
    val showCreateFolderDialog: Boolean = false,
    val isCreatingFolder: Boolean = false,
    // Upload state
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val uploadComplete: Boolean = false,
    val error: String? = null,
)

class ShareUploadViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val configRepository = ConfigRepository.getInstance(application)
    private val s3Service = S3Service.getInstance()
    private val transferManager = TransferManager.getInstance(application)

    private val _uiState = MutableStateFlow(ShareUploadUiState())
    val uiState: StateFlow<ShareUploadUiState> = _uiState.asStateFlow()

    private var currentConfig: S3Config? = null

    fun initialize() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Load config
                val config = configRepository.configFlow.first()

                if (!config.isValid()) {
                    _uiState.update { it.copy(
                            isLoading = false,
                            hasValidConfig = false,
                        ) }
                    return@launch
                }

                currentConfig = config

                // Initialize S3 service
                s3Service.initialize(config)

                // List buckets
                val bucketsResult = s3Service.listBuckets()
                val bucketNames = bucketsResult.getOrNull()?.map { it.name } ?: emptyList()

                // Get last used bucket from saved nav states
                val lastBucket = bucketNames.firstOrNull()

                _uiState.update { it.copy(
                        isLoading = false,
                        hasValidConfig = true,
                        buckets = bucketNames,
                        selectedBucket = lastBucket,
                    ) }

                // Load folders for the selected bucket
                lastBucket?.let { loadFolders() }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                        isLoading = false,
                        hasValidConfig = false,
                        error = ErrorUtils.formatError(e),
                    ) }
            }
        }
    }

    fun selectBucket(bucket: String) {
        _uiState.update { it.copy(
                selectedBucket = bucket,
                currentPath = "",
                pathHistory = listOf(""),
                folders = emptyList(),
            ) }
        viewModelScope.launch {
            loadFolders()
        }
    }

    private suspend fun loadFolders() {
        val bucket = _uiState.value.selectedBucket ?: return

        _uiState.update { it.copy(isLoadingFolders = true) }

        try {
            val result =
                s3Service.listObjects(
                    bucketName = bucket,
                    prefix = _uiState.value.currentPath,
                    delimiter = "/",
                )

            val objects = result.getOrNull() ?: emptyList()
            // Only show folders
            val folders = objects.filter { it.isFolder }

            _uiState.update { it.copy(
                    folders = folders,
                    isLoadingFolders = false,
                ) }
        } catch (e: Exception) {
            _uiState.update { it.copy(
                    isLoadingFolders = false,
                    error = "Failed to load folders: ${ErrorUtils.formatError(e)}",
                ) }
        }
    }

    fun navigateToFolder(folderKey: String) {
        val newHistory = _uiState.value.pathHistory + folderKey
        _uiState.update { it.copy(
                currentPath = folderKey,
                pathHistory = newHistory,
            ) }
        viewModelScope.launch {
            loadFolders()
        }
    }

    fun navigateUp(): Boolean {
        val history = _uiState.value.pathHistory
        if (history.size <= 1) return false

        val newHistory = history.dropLast(1)
        val newPath = newHistory.lastOrNull() ?: ""

        _uiState.update { it.copy(
                currentPath = newPath,
                pathHistory = newHistory,
            ) }
        viewModelScope.launch {
            loadFolders()
        }
        return true
    }

    fun navigateToRoot() {
        _uiState.update { it.copy(
                currentPath = "",
                pathHistory = listOf(""),
            ) }
        viewModelScope.launch {
            loadFolders()
        }
    }

    // Create folder dialog
    fun showCreateFolderDialog() {
        _uiState.update { it.copy(showCreateFolderDialog = true) }
    }

    fun hideCreateFolderDialog() {
        _uiState.update { it.copy(showCreateFolderDialog = false) }
    }

    fun createFolder(folderName: String) {
        val bucket = _uiState.value.selectedBucket ?: return
        val currentPath = _uiState.value.currentPath

        // Sanitize folder name and join it onto the current path
        val pathKey = ObjectKey(currentPath)
        val folder = pathKey.child(folderName)
        if (folder == pathKey || folder.key.isEmpty()) return

        val folderKey = folder.asFolder().key

        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingFolder = true) }

            try {
                val result = s3Service.createFolder(bucket, folderKey)

                if (result.isSuccess) {
                    _uiState.update { it.copy(
                            isCreatingFolder = false,
                            showCreateFolderDialog = false,
                        ) }
                    // Refresh folder list
                    loadFolders()
                } else {
                    _uiState.update { it.copy(
                            isCreatingFolder = false,
                            error = "Failed to create folder: ${result.exceptionOrNull()?.message}",
                        ) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                        isCreatingFolder = false,
                        error = "Failed to create folder: ${ErrorUtils.formatError(e)}",
                    ) }
            }
        }
    }

    /**
     * Upload the shared files to the selected bucket/path. Execution lives in
     * the TransferManager's in-process adapter (survives Activity teardown,
     * gains record tracking + cancellation); this ViewModel mirrors the
     * record onto the screen's progress text.
     */
    fun uploadFiles(files: List<SharedFileInfo>) {
        val bucket = _uiState.value.selectedBucket ?: return

        val transferId =
            transferManager.enqueueShareUpload(
                files = files,
                bucket = bucket,
                prefix = _uiState.value.currentPath,
            )

        _uiState.update { it.copy(
            isUploading = true,
            uploadProgress = "Preparing upload...",
        ) }

        viewModelScope.launch {
            val terminal =
                transferManager.records
                    .map { it[transferId] }
                    .filterNotNull()
                    .onEach { record ->
                        if (record.state == TransferState.ACTIVE) {
                            _uiState.update { it.copy(uploadProgress = record.status) }
                        }
                    }.first { it.state != TransferState.ACTIVE }

            when (terminal.state) {
                // Partial success lands COMPLETED with an error note (shown as
                // snackbar before the completion toast finishes the activity).
                TransferState.COMPLETED ->
                    _uiState.update { it.copy(
                        isUploading = false,
                        uploadComplete = true,
                        error = terminal.error,
                    ) }

                TransferState.FAILED ->
                    _uiState.update { it.copy(
                        isUploading = false,
                        error = terminal.error ?: "All uploads failed. Please check your connection and try again.",
                    ) }

                else -> // CANCELLED
                    _uiState.update { it.copy(isUploading = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
