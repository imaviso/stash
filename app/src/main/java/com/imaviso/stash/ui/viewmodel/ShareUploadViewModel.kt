package com.imaviso.stash.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.ui.screens.SharedFileInfo
import com.imaviso.stash.util.ErrorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val context = application.applicationContext
    private val configRepository = ConfigRepository(application)
    private val s3Service = S3Service()

    private val _uiState = MutableStateFlow(ShareUploadUiState())
    val uiState: StateFlow<ShareUploadUiState> = _uiState.asStateFlow()

    private var currentConfig: S3Config? = null

    fun initialize() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Load config
                val config = configRepository.configFlow.first()

                if (!config.isValid()) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            hasValidConfig = false,
                        )
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

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        hasValidConfig = true,
                        buckets = bucketNames,
                        selectedBucket = lastBucket,
                    )

                // Load folders for the selected bucket
                lastBucket?.let { loadFolders() }
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        hasValidConfig = false,
                        error = ErrorUtils.formatError(e),
                    )
            }
        }
    }

    fun selectBucket(bucket: String) {
        _uiState.value =
            _uiState.value.copy(
                selectedBucket = bucket,
                currentPath = "",
                pathHistory = listOf(""),
                folders = emptyList(),
            )
        viewModelScope.launch {
            loadFolders()
        }
    }

    private suspend fun loadFolders() {
        val bucket = _uiState.value.selectedBucket ?: return

        _uiState.value = _uiState.value.copy(isLoadingFolders = true)

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

            _uiState.value =
                _uiState.value.copy(
                    folders = folders,
                    isLoadingFolders = false,
                )
        } catch (e: Exception) {
            _uiState.value =
                _uiState.value.copy(
                    isLoadingFolders = false,
                    error = "Failed to load folders: ${ErrorUtils.formatError(e)}",
                )
        }
    }

    fun navigateToFolder(folderKey: String) {
        val newHistory = _uiState.value.pathHistory + folderKey
        _uiState.value =
            _uiState.value.copy(
                currentPath = folderKey,
                pathHistory = newHistory,
            )
        viewModelScope.launch {
            loadFolders()
        }
    }

    fun navigateUp(): Boolean {
        val history = _uiState.value.pathHistory
        if (history.size <= 1) return false

        val newHistory = history.dropLast(1)
        val newPath = newHistory.lastOrNull() ?: ""

        _uiState.value =
            _uiState.value.copy(
                currentPath = newPath,
                pathHistory = newHistory,
            )
        viewModelScope.launch {
            loadFolders()
        }
        return true
    }

    fun navigateToRoot() {
        _uiState.value =
            _uiState.value.copy(
                currentPath = "",
                pathHistory = listOf(""),
            )
        viewModelScope.launch {
            loadFolders()
        }
    }

    // Create folder dialog
    fun showCreateFolderDialog() {
        _uiState.value = _uiState.value.copy(showCreateFolderDialog = true)
    }

    fun hideCreateFolderDialog() {
        _uiState.value = _uiState.value.copy(showCreateFolderDialog = false)
    }

    fun createFolder(folderName: String) {
        val bucket = _uiState.value.selectedBucket ?: return
        val currentPath = _uiState.value.currentPath

        // Sanitize folder name
        val sanitizedName = folderName.trim().replace("/", "")
        if (sanitizedName.isEmpty()) return

        val folderKey = "$currentPath$sanitizedName/"

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreatingFolder = true)

            try {
                val result = s3Service.createFolder(bucket, folderKey)

                if (result.isSuccess) {
                    _uiState.value =
                        _uiState.value.copy(
                            isCreatingFolder = false,
                            showCreateFolderDialog = false,
                        )
                    // Refresh folder list
                    loadFolders()
                } else {
                    _uiState.value =
                        _uiState.value.copy(
                            isCreatingFolder = false,
                            error = "Failed to create folder: ${result.exceptionOrNull()?.message}",
                        )
                }
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isCreatingFolder = false,
                        error = "Failed to create folder: ${ErrorUtils.formatError(e)}",
                    )
            }
        }
    }

    suspend fun uploadFiles(
        context: Context,
        files: List<SharedFileInfo>,
    ) {
        val bucket = _uiState.value.selectedBucket ?: return
        val path = _uiState.value.currentPath

        _uiState.value =
            _uiState.value.copy(
                isUploading = true,
                uploadProgress = "Preparing upload...",
            )

        try {
            var successCount = 0

            files.forEachIndexed { index, file ->
                _uiState.value =
                    _uiState.value.copy(
                        uploadProgress = "Uploading ${index + 1}/${files.size}: ${file.fileName}",
                    )

                // Read file data
                val data =
                    context.contentResolver.openInputStream(file.uri)?.use {
                        it.readBytes()
                    }

                if (data != null) {
                    val objectKey = "$path${file.fileName}"

                    val result =
                        s3Service.uploadObject(
                            bucketName = bucket,
                            key = objectKey,
                            data = data,
                            contentType = file.mimeType,
                        )

                    if (result.isSuccess) {
                        successCount++
                    } else {
                        // Continue with other files even if one fails
                        android.util.Log.e(
                            "ShareUpload",
                            "Failed to upload ${file.fileName}: ${result.exceptionOrNull()?.message}",
                        )
                    }
                }
            }

            if (successCount == files.size) {
                _uiState.value =
                    _uiState.value.copy(
                        isUploading = false,
                        uploadComplete = true,
                    )
            } else if (successCount > 0) {
                _uiState.value =
                    _uiState.value.copy(
                        isUploading = false,
                        error = "Uploaded $successCount/${files.size} files. Some uploads failed.",
                        uploadComplete = true,
                    )
            } else {
                _uiState.value =
                    _uiState.value.copy(
                        isUploading = false,
                        error = "All uploads failed. Please check your connection and try again.",
                    )
            }
        } catch (e: Exception) {
            _uiState.value =
                _uiState.value.copy(
                    isUploading = false,
                    error = "Upload failed: ${ErrorUtils.formatError(e)}",
                )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
