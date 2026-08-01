package com.imaviso.stash.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imaviso.stash.data.ObjectOperations
import com.imaviso.stash.data.PendingDeletes
import com.imaviso.stash.data.model.ObjectKey
import com.imaviso.stash.data.model.FileType
import com.imaviso.stash.data.model.S3Config
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.model.StorageStats
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.data.transfer.TransferInfo
import com.imaviso.stash.data.transfer.TransferManager
import com.imaviso.stash.data.transfer.TransferState
import com.imaviso.stash.ui.preview.PreviewController
import com.imaviso.stash.util.DownloadsSaver
import com.imaviso.stash.util.ErrorUtils
import com.imaviso.stash.util.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * One-shot snackbar event with an optional action (e.g. Undo). Emitted via
 * [ObjectsViewModel.snackbarEvents] and collected by the screen so transient
 * prompts don't live in [ObjectsUiState].
 */
data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

enum class ClipboardAction {
    COPY,
    MOVE,
}

data class ClipboardData(
    val sourceBucket: String,
    val objects: List<S3Object>,
    val action: ClipboardAction,
)

enum class SortOption(
    val displayName: String,
) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    SIZE_DESC("Largest first"),
    SIZE_ASC("Smallest first"),
}

enum class ViewMode {
    LIST,
    GRID,
}

/**
 * Share URL expiration options
 */
enum class ShareExpiration(
    val displayName: String,
    val duration: Duration,
) {
    ONE_HOUR("1 hour", 1.hours),
    SIX_HOURS("6 hours", 6.hours),
    ONE_DAY("1 day", 1.days),
    SEVEN_DAYS("7 days", 7.days),
}

data class ObjectsUiState(
    val bucketName: String = "",
    val currentPrefix: String = "",
    val objects: List<S3Object> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val selectedObject: S3Object? = null,
    val isDeleting: Boolean = false,
    val showUploadDialog: Boolean = false,
    // Multiple concurrent transfers
    val activeTransfers: List<TransferInfo> = emptyList(),
    // Legacy single-file progress (for non-background transfers)
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val uploadProgressPercent: Float = 0f,
    val downloadProgress: String = "",
    val downloadProgressPercent: Float = 0f,
    val isDownloading: Boolean = false,
    val pathHistory: List<String> = listOf(""),
    // Scroll state
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    // Multi-select
    val isMultiSelectMode: Boolean = false,
    val selectedObjects: Set<S3Object> = emptySet(),
    // Clipboard for copy/move
    val clipboard: ClipboardData? = null,
    val isPasting: Boolean = false,
    val pasteProgress: String = "",
    // Search/filter
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isSearchRecursive: Boolean = false,
    val recursiveResults: List<S3Object> = emptyList(),
    val isSearchingRecursive: Boolean = false,
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
    val detailsObject: S3Object? = null,
    // Share dialog
    val showShareDialog: Boolean = false,
    val shareObject: S3Object? = null,
    val shareUrl: String? = null,
    val isGeneratingShareUrl: Boolean = false,
    // Folder operations
    val isDeletingFolder: Boolean = false,
    val folderDeleteProgress: String = "",
    val isDownloadingFolder: Boolean = false,
    val folderDownloadProgress: String = "",
    // Storage stats
    val showStorageStats: Boolean = false,
    val storageStats: StorageStats? = null,
    val isLoadingStats: Boolean = false,
    // Undo-able single-file delete
    val pendingDeleteObject: S3Object? = null,
)

class ObjectsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val configRepository = ConfigRepository.getInstance(application)
    private val s3Service = S3Service.getInstance()
    private val objectOperations = ObjectOperations(s3Service)
    private val transferManager = TransferManager.getInstance(application)

    // Navigation back-stack + persistence (deep core in NavigationHistory)
    private val navigation =
        NavigationHistory { bucket, state ->
            configRepository.saveBucketNavState(
                bucket,
                state.currentPrefix,
                state.pathHistory,
                state.scrollIndex,
                state.scrollOffset,
            )
        }

    // Presigned thumbnail URLs, TTL'd + cleared on account switch
    private val thumbnailCache = ThumbnailCache()

    // Undo-able deletes commit from the application scope (survive navigation)
    private val pendingDeletes = PendingDeletes.getInstance()

    private val _uiState = MutableStateFlow(ObjectsUiState())
    val uiState: StateFlow<ObjectsUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 5)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    // Current running job for cancellation
    private var currentJob: Job? = null

    // In-process (foreground) transfer id surfaced on the upload overlay.
    private var foregroundTransferId: String? = null

    init {
        // Transfer records live in the TransferManager (single authority);
        // the screen observes their active subset through uiState.
        viewModelScope.launch {
            transferManager.activeTransfers.collect { active ->
                _uiState.update { it.copy(activeTransfers = active) }
            }
        }
        // Undo-able delete commit outcomes arrive on the application scope.
        viewModelScope.launch {
            pendingDeletes.commits.collect { result ->
                when (result) {
                    is PendingDeletes.CommitResult.Succeeded -> {
                        _uiState.update { it.copy(pendingDeleteObject = null) }
                        loadObjects()
                    }

                    is PendingDeletes.CommitResult.Failed -> {
                        _uiState.update { it.copy(
                                pendingDeleteObject = null,
                                error = ErrorUtils.formatError(result.cause),
                            ) }
                    }
                }
            }
        }
        // Observe network connectivity
        viewModelScope.launch {
            NetworkUtils.observeNetworkConnectivity(context).collect { isConnected ->
                _uiState.update { it.copy(isOffline = !isConnected) }
                // Auto-refresh when coming back online
                if (isConnected && _uiState.value.bucketName.isNotEmpty() && _uiState.value.objects.isEmpty()) {
                    refresh()
                }
            }
        }
    }

    /**
     * Check network before performing an operation
     * Returns true if network is available, false if offline (and sets error)
     */
    private fun checkNetwork(): Boolean {
        val offlineError = NetworkUtils.offlineErrorIfUnavailable(context) ?: return true
        _uiState.update {
            it.copy(
                isOffline = true,
                error = offlineError,
            )
        }
        return false
    }

    fun setBucket(bucketName: String) {
        viewModelScope.launch {
            // Try to restore saved navigation state for this bucket
            val savedState =
                configRepository.getBucketNavState(bucketName)?.let {
                    if (it.pathHistory.isEmpty()) null else NavState(it.currentPrefix, it.pathHistory, it.scrollIndex, it.scrollOffset)
                }
            navigation.attach(bucketName, savedState)
            _uiState.update { it.copy(bucketName = bucketName) }
            syncNavToUiState()
            initializeAndLoad()
        }
    }

    /**
     * Mirror the NavigationHistory core into uiState (multi-select resets on
     * every navigation, as before).
     */
    private fun syncNavToUiState() {
        val nav = navigation.state
        _uiState.update { it.copy(
                currentPrefix = nav.currentPrefix,
                pathHistory = nav.pathHistory,
                scrollIndex = nav.scrollIndex,
                scrollOffset = nav.scrollOffset,
                isMultiSelectMode = false,
                selectedObjects = emptySet(),
            ) }
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
            // Account switch: presigned thumbnail URLs are per-account, drop stale entries
            if (lastInitConfig != null && lastInitConfig != config) {
                thumbnailCache.clear()
            }
            lastInitConfig = config
            loadObjects()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.update { it.copy(
                error = ErrorUtils.formatError(e),
            ) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!checkNetwork()) return@launch
            loadObjects()
        }
    }

    private suspend fun loadObjects() {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            _uiState.update { it.copy(
                isLoading = false,
                isOffline = true,
                error = NetworkUtils.NO_CONNECTION_ERROR,
            ) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null, isOffline = false) }

        s3Service
            .listObjects(
                bucketName = _uiState.value.bucketName,
                prefix = _uiState.value.currentPrefix,
            ).onSuccess { objects ->
                _uiState.update { it.copy(
                        objects = objects,
                        isLoading = false,
                        recursiveResults = emptyList(),
                    ) }
                // Re-run recursive search if active so results match new listing.
                if (_uiState.value.isSearchRecursive && _uiState.value.searchQuery.isNotBlank()) {
                    maybeRunRecursiveSearch()
                }
            }.onFailure { e ->
                _uiState.update { it.copy(
                        isLoading = false,
                        error = ErrorUtils.formatError(e),
                    ) }
            }
    }

    // ==================== OPERATION PLUMBING ====================

    /**
     * The shared single-call operation template: set start flags → run the
     * call → fold the result into state → reload the listing on success.
     * New operations cost ~3 lines. Not every operation fits: multi-call or
     * progress-carrying flows keep bespoke bodies.
     */
    private fun <T> runOp(
        reload: Boolean = true,
        onStart: ObjectsUiState.() -> ObjectsUiState = { this },
        onSuccess: ObjectsUiState.(T) -> ObjectsUiState,
        onFailure: ObjectsUiState.(Throwable) -> ObjectsUiState = { e -> copy(error = ErrorUtils.formatError(e)) },
        block: suspend () -> Result<T>,
    ) {
        viewModelScope.launch {
            _uiState.update { it.onStart() }
            block().fold(
                onSuccess = { value ->
                    _uiState.update { it.onSuccess(value) }
                    if (reload) loadObjects()
                },
                onFailure = { e -> _uiState.update { it.onFailure(e) } },
            )
        }
    }

    /**
     * Mirror a transfer record onto overlays until it reaches a terminal
     * state, then hand the final record to [onTerminal].
     */
    private fun observeTransfer(
        transferId: String,
        onActive: (TransferInfo) -> Unit,
        onTerminal: suspend (TransferInfo) -> Unit,
    ) {
        viewModelScope.launch {
            val terminal =
                transferManager.records
                    .map { it[transferId] }
                    .filterNotNull()
                    .onEach { record ->
                        if (record.state == TransferState.ACTIVE) onActive(record)
                    }.first { it.state != TransferState.ACTIVE }
            onTerminal(terminal)
        }
    }

    /**
     * Shared foreground download plumbing: progress overlay → stream to a
     * cache file → fold. The dest file is deleted on failure; success
     * handling (save to Downloads, keep for Open With) belongs to [onDone].
     */
    private fun downloadToFile(
        obj: S3Object,
        verb: String,
        makeDestFile: (cacheDir: java.io.File) -> java.io.File,
        failureMessage: (Throwable) -> String,
        onDone: (java.io.File) -> Unit,
    ) {
        currentJob =
            viewModelScope.launch {
                _uiState.update { it.copy(
                        isDownloading = true,
                        downloadProgress = "$verb ${obj.fileName}...",
                        downloadProgressPercent = 0f,
                        canCancel = true,
                    ) }

                val destFile = makeDestFile(getApplication<android.app.Application>().cacheDir)

                s3Service
                    .downloadObjectToFile(
                        bucketName = _uiState.value.bucketName,
                        key = obj.key,
                        destFile = destFile,
                        expectedSize = obj.size,
                        onProgress = { bytesWritten, totalBytes ->
                            val percent = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) else 0f
                            val mbWritten = bytesWritten / (1024 * 1024f)
                            val mbTotal = totalBytes / (1024 * 1024f)
                            _uiState.update { it.copy(
                                    downloadProgress =
                                        "$verb: %.1f / %.1f MB (%d%%)".format(
                                            mbWritten,
                                            mbTotal,
                                            (percent * 100).toInt(),
                                        ),
                                    downloadProgressPercent = percent,
                                ) }
                        },
                    ).onSuccess {
                        _uiState.update { it.copy(
                                isDownloading = false,
                                downloadProgress = "",
                                downloadProgressPercent = 0f,
                                canCancel = false,
                            ) }
                        onDone(destFile)
                    }.onFailure { e ->
                        destFile.delete()
                        _uiState.update { it.copy(
                                isDownloading = false,
                                downloadProgress = "",
                                downloadProgressPercent = 0f,
                                canCancel = false,
                                error = failureMessage(e),
                            ) }
                    }
            }
    }

    fun navigateToFolder(prefix: String) {
        viewModelScope.launch {
            navigation.push(prefix)
            syncNavToUiState()
            loadObjects()
        }
    }

    fun navigateUp(): Boolean {
        if (!navigation.canGoUp) return false

        viewModelScope.launch {
            navigation.pop()
            syncNavToUiState()
            loadObjects()
        }
        return true
    }

    // ==================== MULTI-SELECT ====================

    fun toggleMultiSelectMode() {
        _uiState.update { it.copy(
                isMultiSelectMode = !_uiState.value.isMultiSelectMode,
                selectedObjects = emptySet(),
            ) }
    }

    fun toggleObjectSelection(obj: S3Object) {
        val currentSelected = _uiState.value.selectedObjects
        val newSelected =
            if (obj in currentSelected) {
                currentSelected - obj
            } else {
                currentSelected + obj
            }
        _uiState.update { it.copy(selectedObjects = newSelected) }
    }

    fun selectAll() {
        // Include folders so bulk operations (delete, details) can target them.
        val all = _uiState.value.objects
        _uiState.update { it.copy(selectedObjects = all.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(
                selectedObjects = emptySet(),
                isMultiSelectMode = false,
            ) }
    }

    fun deleteSelectedObjects() {
        val selected = _uiState.value.selectedObjects.toList()
        if (selected.isEmpty()) return

        val folders = selected.filter { it.isFolder }
        val files = selected.filter { !it.isFolder }

        // Composite op: batch files first, then folders; folds to the final error.
        runOp(
            onStart = { copy(isDeleting = true) },
            onSuccess = { failure: String? ->
                copy(
                    isDeleting = false,
                    showDeleteDialog = false,
                    isMultiSelectMode = false,
                    selectedObjects = emptySet(),
                    error = failure,
                )
            },
        ) {
            var failure: String? = null

            // Batch-delete selected files first.
            if (files.isNotEmpty()) {
                s3Service
                    .deleteObjects(_uiState.value.bucketName, files.map { it.key })
                    .onFailure { e -> failure = ErrorUtils.formatError(e) }
            }

            // Recursively delete selected folders.
            for (folder in folders) {
                val result = objectOperations.recursiveDeleteFolder(_uiState.value.bucketName, folder.key)
                if (result.isFailure) {
                    failure = "Failed to delete folder ${folder.fileName}: ${result.exceptionOrNull()?.message}"
                }
            }

            Result.success(failure)
        }
    }

    // ==================== COPY / MOVE ====================

    fun copySelectedObjects() {
        val selected = _uiState.value.selectedObjects.toList()
        if (selected.isEmpty()) return

        if (selected.any { it.isFolder }) {
            _uiState.update { it.copy(
                    error = "Folder copy isn't supported yet. Select files only.",
                ) }
            return
        }

        _uiState.update { it.copy(
                clipboard =
                    ClipboardData(
                        sourceBucket = _uiState.value.bucketName,
                        objects = selected,
                        action = ClipboardAction.COPY,
                    ),
                isMultiSelectMode = false,
                selectedObjects = emptySet(),
            ) }
    }

    fun cutSelectedObjects() {
        val selected = _uiState.value.selectedObjects.toList()
        if (selected.isEmpty()) return

        if (selected.any { it.isFolder }) {
            _uiState.update { it.copy(
                    error = "Folder move isn't supported yet. Select files only.",
                ) }
            return
        }

        _uiState.update { it.copy(
                clipboard =
                    ClipboardData(
                        sourceBucket = _uiState.value.bucketName,
                        objects = selected,
                        action = ClipboardAction.MOVE,
                    ),
                isMultiSelectMode = false,
                selectedObjects = emptySet(),
            ) }
    }

    fun paste() {
        val clipboard = _uiState.value.clipboard ?: return

        // Composite op: pasteObjects counts per-item failures (returns failCount).
        runOp(
            onStart = { copy(isPasting = true, pasteProgress = "Pasting ${clipboard.objects.size} items...") },
            onSuccess = { failCount: Int ->
                copy(
                    isPasting = false,
                    pasteProgress = "",
                    clipboard = if (clipboard.action == ClipboardAction.MOVE) null else clipboard,
                    error = if (failCount > 0) "Failed to paste $failCount items" else null,
                )
            },
        ) {
            Result.success(
                objectOperations.pasteObjects(
                    sourceBucket = clipboard.sourceBucket,
                    destBucket = _uiState.value.bucketName,
                    destPrefix = _uiState.value.currentPrefix,
                    objects = clipboard.objects,
                    deleteSourceAfterCopy = clipboard.action == ClipboardAction.MOVE,
                    onProgress = { successCount, total ->
                        _uiState.update {
                            it.copy(
                                pasteProgress = "Pasted $successCount of $total...",
                            )
                        }
                    },
                ),
            )
        }
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
    }

    // ==================== PREVIEW ====================

    // Active preview session; null = no preview. The controller owns policy
    // (stream vs bytes, size cap) and per-item source resolution.
    private val _previewController = MutableStateFlow<PreviewController?>(null)
    val previewController: StateFlow<PreviewController?> = _previewController.asStateFlow()

    fun openPreview(obj: S3Object) {
        if (obj.isFolder) return

        // Build list of previewable files (non-folders) and find current index
        val previewableFiles = sortObjects(_uiState.value.objects).filter { !it.isFolder }
        val index = previewableFiles.indexOfFirst { it.key == obj.key }.coerceAtLeast(0)

        _previewController.value =
            PreviewController(
                s3 = s3Service,
                bucketName = _uiState.value.bucketName,
                objects = previewableFiles,
                initialIndex = index,
                scope = viewModelScope,
            )
    }

    /**
     * Navigate to a specific index in the preview session (used by pager)
     */
    fun navigateToPreviewIndex(index: Int) {
        _previewController.value?.select(index)
    }

    fun closePreview() {
        _previewController.value = null
    }

    // ==================== DELETE ====================

    fun showDeleteDialog(obj: S3Object) {
        _uiState.update { it.copy(
                showDeleteDialog = true,
                selectedObject = obj,
            ) }
    }

    fun showDeleteDialogForSelected() {
        if (_uiState.value.selectedObjects.isEmpty()) return
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun hideDeleteDialog() {
        _uiState.update { it.copy(
                showDeleteDialog = false,
                selectedObject = null,
            ) }
    }

    fun deleteObject() {
        // If in multi-select mode, delete all selected (no undo - bulk op)
        if (_uiState.value.isMultiSelectMode && _uiState.value.selectedObjects.isNotEmpty()) {
            deleteSelectedObjects()
            return
        }

        // Single object delete - support undo via delayed commit
        val obj = _uiState.value.selectedObject ?: return

        // Folder deletes are not undo-able (recursive); commit immediately via
        // the existing recursive path.
        if (obj.isFolder) {
            deleteFolderRecursively(obj)
            return
        }

        // Commit runs on the application scope (PendingDeletes) so navigating
        // away does not cancel it; a new schedule cancels the previous pending
        // delete, as before.
        pendingDeletes.schedule(_uiState.value.bucketName, obj.key)
        _uiState.update { it.copy(
                showDeleteDialog = false,
                selectedObject = null,
                pendingDeleteObject = obj,
            ) }

        viewModelScope.launch {
            _snackbarEvents.emit(SnackbarEvent("Deleted ${obj.fileName}", "Undo", onAction = ::undoDelete))
        }
    }

    /**
     * Cancel a pending single-file delete triggered by an Undo snackbar action.
     */
    fun undoDelete() {
        pendingDeletes.cancelPending()
        if (_uiState.value.pendingDeleteObject != null) {
            _uiState.update { it.copy(
                    pendingDeleteObject = null,
                ) }
            viewModelScope.launch { _snackbarEvents.emit(SnackbarEvent("Delete cancelled")) }
        }
    }

    companion object {
        private const val RECURSIVE_SEARCH_DEBOUNCE_MS = 400L
    }

    // ==================== UPLOAD / DOWNLOAD ====================

    /**
     * Upload [uri] into the current bucket/prefix. Execution dispatch (and the
     * >5MB background-routing rule) is owned by the TransferManager; this
     * ViewModel only surfaces progress on the upload overlay for the
     * in-process (foreground) path.
     */
    fun uploadFile(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        contentType: String,
    ) {
        if (transferManager.shouldRunInBackground(fileSize)) {
            transferManager.enqueueUpload(
                fileUri = uri.toString(),
                bucket = _uiState.value.bucketName,
                prefix = _uiState.value.currentPrefix,
                fileName = fileName,
                size = fileSize,
                contentType = contentType,
            )
            _uiState.update { it.copy(showUploadDialog = false) }
            return
        }

        val transferId =
            transferManager.enqueueUpload(
                fileUri = uri.toString(),
                bucket = _uiState.value.bucketName,
                prefix = _uiState.value.currentPrefix,
                fileName = fileName,
                size = fileSize,
                contentType = contentType,
                background = false,
            )

        foregroundTransferId = transferId
        _uiState.update { it.copy(
                isUploading = true,
                uploadProgress = "Uploading $fileName...",
                uploadProgressPercent = 0f,
                canCancel = true,
            ) }

        observeTransfer(
            transferId,
            onActive = { record ->
                _uiState.update { it.copy(
                        uploadProgress = "${record.status} $fileName",
                        uploadProgressPercent = record.progress / 100f,
                    ) }
            },
        ) { terminal ->
            foregroundTransferId = null
            when (terminal.state) {
                TransferState.COMPLETED -> {
                    _uiState.update { it.copy(
                            isUploading = false,
                            uploadProgress = "",
                            uploadProgressPercent = 0f,
                            showUploadDialog = false,
                            canCancel = false,
                        ) }
                    loadObjects()
                }

                TransferState.FAILED ->
                    _uiState.update { it.copy(
                            isUploading = false,
                            uploadProgress = "",
                            uploadProgressPercent = 0f,
                            canCancel = false,
                            error = "Upload failed: ${terminal.error}",
                        ) }

                else -> // CANCELLED
                    _uiState.update { it.copy(
                            isUploading = false,
                            uploadProgress = "",
                            uploadProgressPercent = 0f,
                            canCancel = false,
                        ) }
            }
        }
    }

    /**
     * Download an object and save it to the public Downloads directory.
     * Streams from a temp file into the MediaStore output stream (no full-file
     * RAM buffering). [onComplete] receives a user-facing result message.
     */
    fun downloadObject(
        obj: S3Object,
        onComplete: (String) -> Unit,
    ) {
        downloadToFile(
            obj = obj,
            verb = "Downloading",
            makeDestFile = { cacheDir -> java.io.File(cacheDir, "download_${System.currentTimeMillis()}_${obj.fileName}") },
            failureMessage = { e -> "Download failed: ${e.message}" },
        ) { tempFile ->
            val message =
                try {
                    DownloadsSaver.saveToDownloads(context, tempFile, obj.fileName, obj.mimeType)
                    "Saved to Downloads: ${obj.fileName}"
                } catch (e: Exception) {
                    "Failed to save: ${e.message}"
                }
            tempFile.delete()
            onComplete(message)
        }
    }

    // ==================== BACKGROUND TRANSFERS (WorkManager) ====================

    /**
     * Download a file in the background using WorkManager.
     * The download will continue even if the app is backgrounded.
     * File is saved to the Downloads folder.
     */
    fun downloadFileInBackground(obj: S3Object) {
        if (obj.isFolder) return

        transferManager.enqueueDownload(
            bucket = _uiState.value.bucketName,
            key = obj.key,
            fileName = obj.fileName,
            size = obj.size,
            mimeType = obj.mimeType,
        )
    }

    /**
     * Cancel a specific transfer — background work or in-process job,
     * the TransferManager dispatches to the owning adapter.
     */
    fun cancelTransfer(transferId: String) {
        transferManager.cancel(transferId)
    }

    /**
     * Cancel all pending/running transfers (any adapter).
     */
    fun cancelBackgroundTransfers() {
        transferManager.records.value.values
            .filter { it.state == TransferState.ACTIVE }
            .forEach { transferManager.cancel(it.id) }
        _uiState.update { it.copy(
                isUploading = false,
                isDownloading = false,
                uploadProgress = "",
                downloadProgress = "",
                uploadProgressPercent = 0f,
                downloadProgressPercent = 0f,
            ) }
    }

    // ==================== SEARCH / FILTER ====================

    // Debounce job for recursive search; cancelled on new query / navigation.
    private var recursiveSearchJob: Job? = null

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        maybeRunRecursiveSearch()
    }

    fun toggleSearch() {
        val isActive = !_uiState.value.isSearchActive
        _uiState.update { it.copy(
                isSearchActive = isActive,
                searchQuery = if (!isActive) "" else _uiState.value.searchQuery,
                recursiveResults = emptyList(),
            ) }
        if (isActive && _uiState.value.isSearchRecursive) {
            maybeRunRecursiveSearch()
        }
    }

    fun clearSearch() {
        recursiveSearchJob?.cancel()
        _uiState.update { it.copy(
                searchQuery = "",
                isSearchActive = false,
                isSearchRecursive = false,
                recursiveResults = emptyList(),
                isSearchingRecursive = false,
            ) }
    }

    fun toggleSearchRecursive() {
        val nowOn = !_uiState.value.isSearchRecursive
        _uiState.update { it.copy(
                isSearchRecursive = nowOn,
                recursiveResults = emptyList(),
            ) }
        if (nowOn) {
            maybeRunRecursiveSearch()
        } else {
            recursiveSearchJob?.cancel()
            _uiState.update { it.copy(isSearchingRecursive = false) }
        }
    }

    /**
     * Run a recursive search if a query is present and recursive mode is on.
     * Debounced so typing doesn't fire a listObjectsRecursive per keystroke.
     */
    private fun maybeRunRecursiveSearch() {
        val query = _uiState.value.searchQuery.trim()
        recursiveSearchJob?.cancel()
        if (!_uiState.value.isSearchRecursive || query.isBlank()) {
            _uiState.update { it.copy(recursiveResults = emptyList(), isSearchingRecursive = false) }
            return
        }
        val bucket = _uiState.value.bucketName
        val prefix = _uiState.value.currentPrefix
        recursiveSearchJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isSearchingRecursive = true) }
                kotlinx.coroutines.delay(RECURSIVE_SEARCH_DEBOUNCE_MS)
                s3Service
                    .listObjectsRecursive(bucket, prefix)
                    .onSuccess { objects ->
                        val matched =
                            objects.filter {
                                it.fileName.contains(query, ignoreCase = true) ||
                                    it.key.contains(query, ignoreCase = true)
                            }
                        _uiState.update { it.copy(
                                recursiveResults = sortObjects(matched),
                                isSearchingRecursive = false,
                            ) }
                    }.onFailure { e ->
                        _uiState.update { it.copy(
                                isSearchingRecursive = false,
                                error = "Search failed: ${e.message}",
                            ) }
                    }
            }
    }

    // ==================== FOLDER CREATION ====================

    fun showCreateFolderDialog() {
        _uiState.update { it.copy(showCreateFolderDialog = true) }
    }

    fun hideCreateFolderDialog() {
        _uiState.update { it.copy(showCreateFolderDialog = false) }
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return

        val folderPath = ObjectKey(_uiState.value.currentPrefix).child(folderName).key
        runOp(
            onStart = { copy(isCreatingFolder = true) },
            onSuccess = { copy(isCreatingFolder = false, showCreateFolderDialog = false) },
            onFailure = { e -> copy(isCreatingFolder = false, error = ErrorUtils.formatError(e)) },
        ) {
            s3Service.createFolder(_uiState.value.bucketName, folderPath)
        }
    }

    // ==================== RENAME ====================

    fun showRenameDialog(obj: S3Object) {
        _uiState.update { it.copy(
                showRenameDialog = true,
                renameObject = obj,
            ) }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(
                showRenameDialog = false,
                renameObject = null,
            ) }
    }

    fun renameObject(newName: String) {
        val obj = _uiState.value.renameObject ?: return
        if (newName.isBlank() || newName == obj.fileName) {
            hideRenameDialog()
            return
        }

        // Construct new key with same prefix but new filename
        val prefix = obj.key.substringBeforeLast(obj.fileName, "")
        val newKey = ObjectKey(prefix).child(newName).key

        runOp(
            onStart = { copy(isRenaming = true) },
            onSuccess = { copy(isRenaming = false, showRenameDialog = false, renameObject = null) },
            onFailure = { e -> copy(isRenaming = false, error = ErrorUtils.formatError(e)) },
        ) {
            objectOperations.renameObject(_uiState.value.bucketName, obj.key, newKey)
        }
    }

    // ==================== CANCELLATION ====================

    fun cancelCurrentOperation() {
        currentJob?.cancel()
        currentJob = null
        foregroundTransferId?.let { transferManager.cancel(it) }
        foregroundTransferId = null
        _uiState.update { it.copy(
                isUploading = false,
                isDownloading = false,
                uploadProgress = "",
                downloadProgress = "",
                uploadProgressPercent = 0f,
                downloadProgressPercent = 0f,
                canCancel = false,
            ) }
    }

    // ==================== THUMBNAILS ====================

    private var lastInitConfig: S3Config? = null

    /**
     * Get a presigned URL for a thumbnail (images and videos).
     * Results are cached (ThumbnailCache) to avoid regenerating URLs.
     */
    suspend fun getThumbnailUrl(obj: S3Object): String? {
        if (obj.fileType != FileType.IMAGE && obj.fileType != FileType.VIDEO) return null

        val bucket = _uiState.value.bucketName
        thumbnailCache.get(bucket, obj.key)?.let { return it }

        return s3Service
            .getPresignedUrl(bucket, obj.key)
            .getOrNull()
            ?.also { url -> thumbnailCache.put(bucket, obj.key, url) }
    }

    // ==================== OPEN WITH ====================

    /**
     * Download file to cache and return the file for opening with external apps
     */
    fun downloadForOpenWith(
        obj: S3Object,
        onReady: (java.io.File) -> Unit,
    ) {
        downloadToFile(
            obj = obj,
            verb = "Preparing",
            makeDestFile = { cacheDir ->
                val sharedDir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                java.io.File(sharedDir, obj.fileName)
            },
            failureMessage = { e -> ErrorUtils.formatError(e) },
        ) { destFile ->
            onReady(destFile)
        }
    }

    fun showUploadDialog() {
        _uiState.update { it.copy(showUploadDialog = true) }
    }

    fun hideUploadDialog() {
        _uiState.update { it.copy(showUploadDialog = false) }
    }

    // ==================== SORTING ====================

    fun setSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun sortObjects(objects: List<S3Object>): List<S3Object> {
        val folders = objects.filter { it.isFolder }
        val files = objects.filter { !it.isFolder }

        // lastModified is nullable - keep null-dated files at the end either way
        val dateComparator = nullsLast<java.util.Date>()
        val sortedFiles =
            when (_uiState.value.sortOption) {
                SortOption.NAME_ASC -> files.sortedBy { it.fileName.lowercase() }
                SortOption.NAME_DESC -> files.sortedByDescending { it.fileName.lowercase() }
                SortOption.DATE_DESC -> files.sortedWith(compareByDescending(dateComparator) { it.lastModified })
                SortOption.DATE_ASC -> files.sortedWith(compareBy(dateComparator) { it.lastModified })
                SortOption.SIZE_DESC -> files.sortedByDescending { it.size }
                SortOption.SIZE_ASC -> files.sortedBy { it.size }
            }

        // Always show folders first, sorted by name
        return folders.sortedBy { it.fileName.lowercase() } + sortedFiles
    }

    // ==================== VIEW MODE ====================

    fun toggleViewMode() {
        val newMode = if (_uiState.value.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        _uiState.update { it.copy(viewMode = newMode) }
    }

    // ==================== FILE DETAILS ====================

    fun showDetailsDialog(obj: S3Object) {
        _uiState.update { it.copy(
                showDetailsDialog = true,
                detailsObject = obj,
            ) }
    }

    fun hideDetailsDialog() {
        _uiState.update { it.copy(
                showDetailsDialog = false,
                detailsObject = null,
            ) }
    }

    // ==================== BREADCRUMB NAVIGATION ====================

    fun navigateToPathSegment(index: Int) {
        viewModelScope.launch {
            if (navigation.jumpTo(index) != null) {
                syncNavToUiState()
                loadObjects()
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ==================== SHARE URL ====================

    fun showShareDialog(obj: S3Object) {
        _uiState.update { it.copy(
                showShareDialog = true,
                shareObject = obj,
                shareUrl = null,
            ) }
    }

    fun hideShareDialog() {
        _uiState.update { it.copy(
                showShareDialog = false,
                shareObject = null,
                shareUrl = null,
                isGeneratingShareUrl = false,
            ) }
    }

    fun generateShareUrl(expiration: ShareExpiration) {
        val obj = _uiState.value.shareObject ?: return

        runOp(
            reload = false,
            onStart = { copy(isGeneratingShareUrl = true) },
            onSuccess = { url -> copy(shareUrl = url, isGeneratingShareUrl = false) },
            onFailure = { e -> copy(isGeneratingShareUrl = false, error = "Failed to generate share URL: ${e.message}") },
        ) {
            s3Service.getPresignedUrl(
                bucketName = _uiState.value.bucketName,
                key = obj.key,
                expiresIn = expiration.duration,
            )
        }
    }

    // ==================== RECURSIVE FOLDER DELETE ====================

    fun deleteFolderRecursively(folder: S3Object) {
        if (!folder.isFolder) return

        runOp(
            onStart = { copy(isDeletingFolder = true, folderDeleteProgress = "Scanning folder contents...") },
            onSuccess = {
                copy(
                    isDeletingFolder = false,
                    folderDeleteProgress = "",
                    showDeleteDialog = false,
                    selectedObject = null,
                )
            },
            onFailure = { e ->
                copy(
                    isDeletingFolder = false,
                    folderDeleteProgress = "",
                    error = "Failed to delete folder: ${e.message}",
                )
            },
        ) {
            objectOperations.deleteFolderRecursively(
                bucketName = _uiState.value.bucketName,
                folderKey = folder.key,
                onProgress = { deleted, total ->
                    _uiState.update {
                        it.copy(
                            folderDeleteProgress = "Deleting $deleted of $total objects...",
                        )
                    }
                },
            )
        }
    }

    // ==================== RECURSIVE FOLDER DOWNLOAD ====================

    /**
     * Download a whole folder into Downloads/<folder>. Execution + transfer
     * record live in the TransferManager (in-process adapter), so the transfer
     * can be cancelled from the Transfers screen or the transfers sheet;
     * here we only mirror the record onto the folder-download overlay.
     */
    fun downloadFolderRecursively(folder: S3Object) {
        if (!folder.isFolder) return

        val transferId =
            transferManager.enqueueFolderDownload(
                bucket = _uiState.value.bucketName,
                folderKey = folder.key,
                folderName = folder.fileName,
            )

        observeTransfer(
            transferId,
            onActive = { record ->
                _uiState.update { it.copy(
                        isDownloadingFolder = true,
                        folderDownloadProgress = record.status,
                    ) }
            },
        ) { terminal ->
            _uiState.update {
                it.copy(
                    isDownloadingFolder = false,
                    folderDownloadProgress = "",
                    error =
                        when (terminal.state) {
                            TransferState.COMPLETED ->
                                "Downloaded folder to Downloads/${folder.fileName}"

                            TransferState.FAILED ->
                                terminal.error ?: "Folder download failed"

                            // CANCELLED carries "Folder is empty" for the empty-folder
                            // case; an actual user cancel surfaces nothing.
                            else -> terminal.error
                        },
                )
            }
        }
    }

    // ==================== STORAGE STATS ====================

    fun showStorageStats() {
        _uiState.update { it.copy(showStorageStats = true) }
        loadStorageStats()
    }

    fun hideStorageStats() {
        _uiState.update { it.copy(
                showStorageStats = false,
                storageStats = null,
            ) }
    }

    private fun loadStorageStats() {
        runOp(
            reload = false,
            onStart = { copy(isLoadingStats = true) },
            onSuccess = { stats -> copy(storageStats = stats, isLoadingStats = false) },
            onFailure = { e -> copy(isLoadingStats = false, error = "Failed to load stats: ${e.message}") },
        ) {
            objectOperations.computeStorageStats(_uiState.value.bucketName, _uiState.value.currentPrefix)
        }
    }

    // ==================== SCROLL STATE ====================

    /**
     * Save scroll position for persistence.
     * Called from UI when scroll position changes.
     */
    fun saveScrollPosition(
        index: Int,
        offset: Int,
    ) {
        _uiState.update { it.copy(
                scrollIndex = index,
                scrollOffset = offset,
            ) }
        // Persist to DataStore via the navigation core
        viewModelScope.launch { navigation.saveScroll(index, offset) }
    }

    override fun onCleared() {
        super.onCleared()
        // S3Service is a process-wide singleton - do not close the shared client here
    }
}
