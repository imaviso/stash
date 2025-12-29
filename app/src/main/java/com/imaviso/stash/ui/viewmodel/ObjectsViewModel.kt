package com.imaviso.stash.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.FileType
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ClipboardAction {
    COPY, MOVE
}

data class ClipboardData(
    val sourceBucket: String,
    val objects: List<S3Object>,
    val action: ClipboardAction
)

enum class SortOption(val displayName: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    SIZE_DESC("Largest first"),
    SIZE_ASC("Smallest first")
}

enum class ViewMode {
    LIST, GRID
}

data class ObjectsUiState(
    val bucketName: String = "",
    val currentPrefix: String = "",
    val objects: List<S3Object> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    val selectedObject: S3Object? = null,
    val isDeleting: Boolean = false,
    val showUploadDialog: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val uploadProgressPercent: Float = 0f,
    val downloadProgress: String = "",
    val downloadProgressPercent: Float = 0f,
    val isDownloading: Boolean = false,
    val pathHistory: List<String> = listOf(""),
    // Multi-select
    val isMultiSelectMode: Boolean = false,
    val selectedObjects: Set<S3Object> = emptySet(),
    // Preview
    val previewObject: S3Object? = null,
    val previewData: ByteArray? = null,
    val previewStreamUrl: String? = null,  // For streaming video/audio
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
    // Clipboard for copy/move
    val clipboard: ClipboardData? = null,
    val isPasting: Boolean = false,
    val pasteProgress: String = "",
    // Search/filter
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    // Folder creation
    val showCreateFolderDialog: Boolean = false,
    val isCreatingFolder: Boolean = false,
    // Rename
    val showRenameDialog: Boolean = false,
    val renameObject: S3Object? = null,
    val isRenaming: Boolean = false,
    // Cancellation
    val canCancel: Boolean = false,
    // Sorting
    val sortOption: SortOption = SortOption.NAME_ASC,
    // View mode
    val viewMode: ViewMode = ViewMode.LIST,
    // File details dialog
    val showDetailsDialog: Boolean = false,
    val detailsObject: S3Object? = null
)

class ObjectsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configRepository = ConfigRepository(application)
    private val s3Service = S3Service()
    
    private val _uiState = MutableStateFlow(ObjectsUiState())
    val uiState: StateFlow<ObjectsUiState> = _uiState.asStateFlow()
    
    // Current running job for cancellation
    private var currentJob: Job? = null
    
    // Filtered objects based on search query
    val filteredObjects: List<S3Object>
        get() {
            val state = _uiState.value
            return if (state.searchQuery.isBlank()) {
                state.objects
            } else {
                state.objects.filter { obj ->
                    obj.fileName.contains(state.searchQuery, ignoreCase = true)
                }
            }
        }
    
    fun setBucket(bucketName: String) {
        _uiState.value = _uiState.value.copy(
            bucketName = bucketName,
            currentPrefix = "",
            pathHistory = listOf(""),
            isMultiSelectMode = false,
            selectedObjects = emptySet()
        )
        initializeAndLoad()
    }
    
    private fun initializeAndLoad() {
        viewModelScope.launch {
            val config = configRepository.configFlow.first()
            if (config.isValid()) {
                initializeService(config)
            }
        }
    }
    
    private suspend fun initializeService(config: S3Config) {
        try {
            s3Service.initialize(config)
            loadObjects()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                error = "Failed to initialize: ${e.message}"
            )
        }
    }
    
    fun refresh() {
        viewModelScope.launch {
            loadObjects()
        }
    }
    
    private suspend fun loadObjects() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        
        s3Service.listObjects(
            bucketName = _uiState.value.bucketName,
            prefix = _uiState.value.currentPrefix
        )
            .onSuccess { objects ->
                _uiState.value = _uiState.value.copy(
                    objects = objects,
                    isLoading = false
                )
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load objects: ${e.message}"
                )
            }
    }
    
    fun navigateToFolder(prefix: String) {
        viewModelScope.launch {
            val newHistory = _uiState.value.pathHistory + prefix
            _uiState.value = _uiState.value.copy(
                currentPrefix = prefix,
                pathHistory = newHistory,
                isMultiSelectMode = false,
                selectedObjects = emptySet()
            )
            loadObjects()
        }
    }
    
    fun navigateUp(): Boolean {
        val history = _uiState.value.pathHistory
        if (history.size <= 1) return false
        
        viewModelScope.launch {
            val newHistory = history.dropLast(1)
            _uiState.value = _uiState.value.copy(
                currentPrefix = newHistory.last(),
                pathHistory = newHistory,
                isMultiSelectMode = false,
                selectedObjects = emptySet()
            )
            loadObjects()
        }
        return true
    }
    
    // ==================== MULTI-SELECT ====================
    
    fun toggleMultiSelectMode() {
        _uiState.value = _uiState.value.copy(
            isMultiSelectMode = !_uiState.value.isMultiSelectMode,
            selectedObjects = emptySet()
        )
    }
    
    fun toggleObjectSelection(obj: S3Object) {
        val currentSelected = _uiState.value.selectedObjects
        val newSelected = if (obj in currentSelected) {
            currentSelected - obj
        } else {
            currentSelected + obj
        }
        _uiState.value = _uiState.value.copy(selectedObjects = newSelected)
    }
    
    fun selectAll() {
        val allFiles = _uiState.value.objects.filter { !it.isFolder }
        _uiState.value = _uiState.value.copy(selectedObjects = allFiles.toSet())
    }
    
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedObjects = emptySet(),
            isMultiSelectMode = false
        )
    }
    
    fun deleteSelectedObjects() {
        val selected = _uiState.value.selectedObjects.toList()
        if (selected.isEmpty()) return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            
            s3Service.deleteObjects(_uiState.value.bucketName, selected.map { it.key })
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        showDeleteDialog = false,
                        isMultiSelectMode = false,
                        selectedObjects = emptySet()
                    )
                    loadObjects()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = "Failed to delete: ${e.message}"
                    )
                }
        }
    }
    
    // ==================== COPY / MOVE ====================
    
    fun copySelectedObjects() {
        val selected = _uiState.value.selectedObjects.toList()
        if (selected.isEmpty()) return
        
        _uiState.value = _uiState.value.copy(
            clipboard = ClipboardData(
                sourceBucket = _uiState.value.bucketName,
                objects = selected,
                action = ClipboardAction.COPY
            ),
            isMultiSelectMode = false,
            selectedObjects = emptySet()
        )
    }
    
    fun cutSelectedObjects() {
        val selected = _uiState.value.selectedObjects.toList()
        if (selected.isEmpty()) return
        
        _uiState.value = _uiState.value.copy(
            clipboard = ClipboardData(
                sourceBucket = _uiState.value.bucketName,
                objects = selected,
                action = ClipboardAction.MOVE
            ),
            isMultiSelectMode = false,
            selectedObjects = emptySet()
        )
    }
    
    fun paste() {
        val clipboard = _uiState.value.clipboard ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPasting = true,
                pasteProgress = "Pasting ${clipboard.objects.size} items..."
            )
            
            var successCount = 0
            var failCount = 0
            
            for (obj in clipboard.objects) {
                val sourceKey = obj.key
                val fileName = obj.fileName
                val destKey = _uiState.value.currentPrefix + fileName
                
                // Copy the object
                val copyResult = s3Service.copyObject(
                    sourceBucket = clipboard.sourceBucket,
                    sourceKey = sourceKey,
                    destBucket = _uiState.value.bucketName,
                    destKey = destKey
                )
                
                if (copyResult.isSuccess) {
                    // If it's a move operation, delete the source
                    if (clipboard.action == ClipboardAction.MOVE) {
                        s3Service.deleteObject(clipboard.sourceBucket, sourceKey)
                    }
                    successCount++
                } else {
                    failCount++
                }
                
                _uiState.value = _uiState.value.copy(
                    pasteProgress = "Pasted $successCount of ${clipboard.objects.size}..."
                )
            }
            
            _uiState.value = _uiState.value.copy(
                isPasting = false,
                pasteProgress = "",
                clipboard = if (clipboard.action == ClipboardAction.MOVE) null else clipboard,
                error = if (failCount > 0) "Failed to paste $failCount items" else null
            )
            
            loadObjects()
        }
    }
    
    fun clearClipboard() {
        _uiState.value = _uiState.value.copy(clipboard = null)
    }
    
    // ==================== PREVIEW ====================
    
    fun openPreview(obj: S3Object) {
        if (obj.isFolder) return
        
        _uiState.value = _uiState.value.copy(
            previewObject = obj,
            previewData = null,
            previewStreamUrl = null,
            isPreviewLoading = true,
            previewError = null
        )
        
        viewModelScope.launch {
            // For video/audio, use presigned URL for streaming instead of downloading
            // This prevents OutOfMemoryError for large media files
            when (obj.fileType) {
                FileType.VIDEO, FileType.AUDIO -> {
                    s3Service.getPresignedUrl(_uiState.value.bucketName, obj.key)
                        .onSuccess { url ->
                            _uiState.value = _uiState.value.copy(
                                previewStreamUrl = url,
                                isPreviewLoading = false
                            )
                        }
                        .onFailure { e ->
                            _uiState.value = _uiState.value.copy(
                                isPreviewLoading = false,
                                previewError = e.message ?: "Failed to get stream URL"
                            )
                        }
                }
                else -> {
                    // For other file types (images, text), download the bytes
                    // Limit download size to prevent OOM (10 MB max for in-memory)
                    if (obj.size > 10 * 1024 * 1024) {
                        _uiState.value = _uiState.value.copy(
                            isPreviewLoading = false,
                            previewError = "File too large for preview (${obj.formattedSize}). Download instead."
                        )
                        return@launch
                    }
                    
                    s3Service.downloadObject(_uiState.value.bucketName, obj.key)
                        .onSuccess { data ->
                            _uiState.value = _uiState.value.copy(
                                previewData = data,
                                isPreviewLoading = false
                            )
                        }
                        .onFailure { e ->
                            _uiState.value = _uiState.value.copy(
                                isPreviewLoading = false,
                                previewError = e.message ?: "Failed to load file"
                            )
                        }
                }
            }
        }
    }
    
    fun closePreview() {
        _uiState.value = _uiState.value.copy(
            previewObject = null,
            previewData = null,
            previewStreamUrl = null,
            isPreviewLoading = false,
            previewError = null
        )
    }
    
    // ==================== DELETE ====================
    
    fun showDeleteDialog(obj: S3Object) {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = true,
            selectedObject = obj
        )
    }
    
    fun showDeleteDialogForSelected() {
        if (_uiState.value.selectedObjects.isEmpty()) return
        _uiState.value = _uiState.value.copy(showDeleteDialog = true)
    }
    
    fun hideDeleteDialog() {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = false,
            selectedObject = null
        )
    }
    
    fun deleteObject() {
        // If in multi-select mode, delete all selected
        if (_uiState.value.isMultiSelectMode && _uiState.value.selectedObjects.isNotEmpty()) {
            deleteSelectedObjects()
            return
        }
        
        // Otherwise delete single object
        val obj = _uiState.value.selectedObject ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            
            s3Service.deleteObject(_uiState.value.bucketName, obj.key)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        showDeleteDialog = false,
                        selectedObject = null
                    )
                    loadObjects()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = "Failed to delete: ${e.message}"
                    )
                }
        }
    }
    
    // ==================== UPLOAD / DOWNLOAD ====================
    
    fun uploadFile(fileName: String, data: ByteArray, contentType: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploading = true,
                uploadProgress = "Uploading $fileName..."
            )
            
            val key = _uiState.value.currentPrefix + fileName
            
            s3Service.uploadObject(_uiState.value.bucketName, key, data, contentType)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadProgress = "",
                        showUploadDialog = false
                    )
                    loadObjects()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadProgress = "",
                        error = "Upload failed: ${e.message}"
                    )
                }
        }
    }
    
    /**
     * Upload file from InputStream - for large files that shouldn't be loaded into memory
     */
    fun uploadFileFromStream(
        fileName: String,
        inputStream: java.io.InputStream,
        contentLength: Long,
        contentType: String
    ) {
        currentJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUploading = true,
                uploadProgress = "Preparing $fileName...",
                uploadProgressPercent = 0f,
                canCancel = true
            )
            
            val key = _uiState.value.currentPrefix + fileName
            val cacheDir = getApplication<android.app.Application>().cacheDir
            
            s3Service.uploadObjectFromStream(
                bucketName = _uiState.value.bucketName,
                key = key,
                inputStream = inputStream,
                contentLength = contentLength,
                contentType = contentType,
                cacheDir = cacheDir,
                onProgress = { bytesWritten, totalBytes, phase ->
                    val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) else 0f
                    val mbWritten = bytesWritten / (1024 * 1024f)
                    val mbTotal = totalBytes / (1024 * 1024f)
                    
                    val progressText = when (phase) {
                        "preparing" -> "Preparing: %.1f / %.1f MB (%d%%)".format(mbWritten, mbTotal, (percent * 100).toInt())
                        "uploading" -> "Uploading $fileName..."
                        "complete" -> "Completing..."
                        else -> "Uploading..."
                    }
                    
                    _uiState.value = _uiState.value.copy(
                        uploadProgress = progressText,
                        uploadProgressPercent = if (phase == "preparing") percent else 1f
                    )
                }
            )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadProgress = "",
                        uploadProgressPercent = 0f,
                        showUploadDialog = false,
                        canCancel = false
                    )
                    loadObjects()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        uploadProgress = "",
                        uploadProgressPercent = 0f,
                        canCancel = false,
                        error = "Upload failed: ${e.message}"
                    )
                }
        }
    }
    
    fun downloadObject(obj: S3Object, onComplete: (ByteArray) -> Unit) {
        currentJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadProgress = "Downloading ${obj.fileName}...",
                downloadProgressPercent = 0f,
                canCancel = true
            )
            
            val cacheDir = getApplication<android.app.Application>().cacheDir
            val tempFile = java.io.File(cacheDir, "download_${System.currentTimeMillis()}_${obj.fileName}")
            
            s3Service.downloadObjectToFile(
                bucketName = _uiState.value.bucketName,
                key = obj.key,
                destFile = tempFile,
                expectedSize = obj.size,
                onProgress = { bytesWritten, totalBytes ->
                    val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) else 0f
                    val mbWritten = bytesWritten / (1024 * 1024f)
                    val mbTotal = totalBytes / (1024 * 1024f)
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = "Downloading: %.1f / %.1f MB (%d%%)".format(mbWritten, mbTotal, (percent * 100).toInt()),
                        downloadProgressPercent = percent
                    )
                }
            )
                .onSuccess {
                    val data = tempFile.readBytes()
                    tempFile.delete()
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadProgress = "",
                        downloadProgressPercent = 0f,
                        canCancel = false
                    )
                    onComplete(data)
                }
                .onFailure { e ->
                    tempFile.delete()
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadProgress = "",
                        downloadProgressPercent = 0f,
                        canCancel = false,
                        error = "Download failed: ${e.message}"
                    )
                }
        }
    }
    
    // ==================== SEARCH / FILTER ====================
    
    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }
    
    fun toggleSearch() {
        val isActive = !_uiState.value.isSearchActive
        _uiState.value = _uiState.value.copy(
            isSearchActive = isActive,
            searchQuery = if (!isActive) "" else _uiState.value.searchQuery
        )
    }
    
    fun clearSearch() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            isSearchActive = false
        )
    }
    
    // ==================== FOLDER CREATION ====================
    
    fun showCreateFolderDialog() {
        _uiState.value = _uiState.value.copy(showCreateFolderDialog = true)
    }
    
    fun hideCreateFolderDialog() {
        _uiState.value = _uiState.value.copy(showCreateFolderDialog = false)
    }
    
    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreatingFolder = true)
            
            val folderPath = _uiState.value.currentPrefix + folderName
            
            s3Service.createFolder(_uiState.value.bucketName, folderPath)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isCreatingFolder = false,
                        showCreateFolderDialog = false
                    )
                    loadObjects()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isCreatingFolder = false,
                        error = "Failed to create folder: ${e.message}"
                    )
                }
        }
    }
    
    // ==================== RENAME ====================
    
    fun showRenameDialog(obj: S3Object) {
        _uiState.value = _uiState.value.copy(
            showRenameDialog = true,
            renameObject = obj
        )
    }
    
    fun hideRenameDialog() {
        _uiState.value = _uiState.value.copy(
            showRenameDialog = false,
            renameObject = null
        )
    }
    
    fun renameObject(newName: String) {
        val obj = _uiState.value.renameObject ?: return
        if (newName.isBlank() || newName == obj.fileName) {
            hideRenameDialog()
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRenaming = true)
            
            // Construct new key with same prefix but new filename
            val prefix = obj.key.substringBeforeLast(obj.fileName, "")
            val newKey = prefix + newName
            
            s3Service.renameObject(_uiState.value.bucketName, obj.key, newKey)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isRenaming = false,
                        showRenameDialog = false,
                        renameObject = null
                    )
                    loadObjects()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isRenaming = false,
                        error = "Failed to rename: ${e.message}"
                    )
                }
        }
    }
    
    // ==================== CANCELLATION ====================
    
    fun cancelCurrentOperation() {
        currentJob?.cancel()
        currentJob = null
        _uiState.value = _uiState.value.copy(
            isUploading = false,
            isDownloading = false,
            uploadProgress = "",
            downloadProgress = "",
            uploadProgressPercent = 0f,
            downloadProgressPercent = 0f,
            canCancel = false
        )
    }
    
    // ==================== THUMBNAILS ====================
    
    // Cache of presigned URLs for thumbnails
    private val thumbnailUrlCache = mutableMapOf<String, String>()
    
    /**
     * Get a presigned URL for a thumbnail image.
     * Results are cached to avoid regenerating URLs.
     */
    suspend fun getThumbnailUrl(obj: S3Object): String? {
        if (obj.fileType != FileType.IMAGE) return null
        
        // Check cache first
        thumbnailUrlCache[obj.key]?.let { return it }
        
        return s3Service.getPresignedUrl(_uiState.value.bucketName, obj.key)
            .getOrNull()
            ?.also { url ->
                thumbnailUrlCache[obj.key] = url
            }
    }
    
    // ==================== OPEN WITH ====================
    
    /**
     * Download file to cache and return the file for opening with external apps
     */
    fun downloadForOpenWith(obj: S3Object, onReady: (java.io.File) -> Unit) {
        currentJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadProgress = "Preparing ${obj.fileName}...",
                downloadProgressPercent = 0f,
                canCancel = true
            )
            
            val cacheDir = getApplication<android.app.Application>().cacheDir
            val sharedDir = java.io.File(cacheDir, "shared").apply { mkdirs() }
            val destFile = java.io.File(sharedDir, obj.fileName)
            
            s3Service.downloadObjectToFile(
                bucketName = _uiState.value.bucketName,
                key = obj.key,
                destFile = destFile,
                expectedSize = obj.size,
                onProgress = { bytesWritten, totalBytes ->
                    val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) else 0f
                    val mbWritten = bytesWritten / (1024 * 1024f)
                    val mbTotal = totalBytes / (1024 * 1024f)
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = "Preparing: %.1f / %.1f MB (%d%%)".format(mbWritten, mbTotal, (percent * 100).toInt()),
                        downloadProgressPercent = percent
                    )
                }
            )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadProgress = "",
                        downloadProgressPercent = 0f,
                        canCancel = false
                    )
                    onReady(destFile)
                }
                .onFailure { e ->
                    destFile.delete()
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadProgress = "",
                        downloadProgressPercent = 0f,
                        canCancel = false,
                        error = "Failed to prepare file: ${e.message}"
                    )
                }
        }
    }
    
    fun showUploadDialog() {
        _uiState.value = _uiState.value.copy(showUploadDialog = true)
    }
    
    fun hideUploadDialog() {
        _uiState.value = _uiState.value.copy(showUploadDialog = false)
    }
    
    // ==================== SORTING ====================
    
    fun setSortOption(option: SortOption) {
        _uiState.value = _uiState.value.copy(sortOption = option)
    }
    
    fun sortObjects(objects: List<S3Object>): List<S3Object> {
        val folders = objects.filter { it.isFolder }
        val files = objects.filter { !it.isFolder }
        
        val sortedFiles = when (_uiState.value.sortOption) {
            SortOption.NAME_ASC -> files.sortedBy { it.fileName.lowercase() }
            SortOption.NAME_DESC -> files.sortedByDescending { it.fileName.lowercase() }
            SortOption.DATE_DESC -> files.sortedByDescending { it.lastModified }
            SortOption.DATE_ASC -> files.sortedBy { it.lastModified }
            SortOption.SIZE_DESC -> files.sortedByDescending { it.size }
            SortOption.SIZE_ASC -> files.sortedBy { it.size }
        }
        
        // Always show folders first, sorted by name
        return folders.sortedBy { it.fileName.lowercase() } + sortedFiles
    }
    
    // ==================== VIEW MODE ====================
    
    fun toggleViewMode() {
        val newMode = if (_uiState.value.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        _uiState.value = _uiState.value.copy(viewMode = newMode)
    }
    
    // ==================== FILE DETAILS ====================
    
    fun showDetailsDialog(obj: S3Object) {
        _uiState.value = _uiState.value.copy(
            showDetailsDialog = true,
            detailsObject = obj
        )
    }
    
    fun hideDetailsDialog() {
        _uiState.value = _uiState.value.copy(
            showDetailsDialog = false,
            detailsObject = null
        )
    }
    
    // ==================== BREADCRUMB NAVIGATION ====================
    
    fun navigateToPathSegment(index: Int) {
        val history = _uiState.value.pathHistory
        if (index < 0 || index >= history.size) return
        
        viewModelScope.launch {
            val newHistory = history.take(index + 1)
            _uiState.value = _uiState.value.copy(
                currentPrefix = newHistory.last(),
                pathHistory = newHistory,
                isMultiSelectMode = false,
                selectedObjects = emptySet()
            )
            loadObjects()
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
