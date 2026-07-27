package com.imaviso.stash.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.imaviso.stash.data.model.FileType
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.ui.viewmodel.ClipboardAction
import com.imaviso.stash.ui.viewmodel.FileTypeStats
import com.imaviso.stash.ui.viewmodel.ObjectsViewModel
import com.imaviso.stash.ui.viewmodel.ShareExpiration
import com.imaviso.stash.ui.viewmodel.SortOption
import com.imaviso.stash.ui.viewmodel.StorageStats
import com.imaviso.stash.data.repository.TransferInfo
import com.imaviso.stash.data.repository.TransferType
import com.imaviso.stash.ui.viewmodel.ViewMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun ObjectsScreen(
    bucketName: String,
    onNavigateBack: () -> Unit,
    viewModel: ObjectsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg ->
        screenScope.launch { snackbarHostState.showSnackbar(msg) }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }

    // Transfers sheet state
    var showTransfersSheet by remember { mutableStateOf(false) }
    val transfersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Scroll state for list and grid views
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = uiState.scrollIndex,
            initialFirstVisibleItemScrollOffset = uiState.scrollOffset,
        )
    val gridState =
        rememberLazyGridState(
            initialFirstVisibleItemIndex = uiState.scrollIndex,
            initialFirstVisibleItemScrollOffset = uiState.scrollOffset,
        )

    // Get filtered and sorted objects from ViewModel
    val displayObjects =
        remember(uiState.objects, uiState.searchQuery, uiState.sortOption, uiState.recursiveResults, uiState.isSearchRecursive) {
            if (uiState.isSearchRecursive && uiState.searchQuery.isNotBlank()) {
                uiState.recursiveResults
            } else {
                val filtered =
                    if (uiState.searchQuery.isBlank()) {
                        uiState.objects
                    } else {
                        uiState.objects.filter { obj ->
                            obj.fileName.contains(uiState.searchQuery, ignoreCase = true)
                        }
                    }
                viewModel.sortObjects(filtered)
            }
        }

    val pullRefreshState =
        rememberPullRefreshState(
            refreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
        )

    // File picker launcher - supports multiple file selection
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            uris.forEach { uri ->
                handleFileUpload(context, uri, viewModel)?.let { showMessage(it) }
            }
        }

    LaunchedEffect(bucketName) {
        viewModel.setBucket(bucketName)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Snackbar events with optional action (e.g. Undo). Action snacks auto-dismiss
    // after the undo window so they don't linger after the delete commits.
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { event ->
            if (event.actionLabel != null) {
                val result =
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Indefinite,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    event.onAction?.invoke()
                }
            } else {
                snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // Dismiss the undo snackbar when the pending delete clears (commit or undo).
    LaunchedEffect(uiState.pendingDeleteObject) {
        if (uiState.pendingDeleteObject == null) {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    // Focus search field when search becomes active
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    // Save scroll position when it changes (debounced to avoid too many writes)
    LaunchedEffect(listState, uiState.viewMode) {
        if (uiState.viewMode == ViewMode.LIST) {
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.debounce(300)
                .collectLatest { (index, offset) ->
                    viewModel.saveScrollPosition(index, offset)
                }
        }
    }

    LaunchedEffect(gridState, uiState.viewMode) {
        if (uiState.viewMode == ViewMode.GRID) {
            snapshotFlow {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }.debounce(300)
                .collectLatest { (index, offset) ->
                    viewModel.saveScrollPosition(index, offset)
                }
        }
    }

    // Restore scroll position when objects are loaded and we have a saved position
    LaunchedEffect(uiState.objects, uiState.scrollIndex) {
        if (uiState.objects.isNotEmpty() && uiState.scrollIndex > 0) {
            if (uiState.viewMode == ViewMode.LIST) {
                listState.scrollToItem(uiState.scrollIndex, uiState.scrollOffset)
            } else {
                gridState.scrollToItem(uiState.scrollIndex, uiState.scrollOffset)
            }
        }
    }

    // Handle system back gesture - navigate up in folders before exiting
    BackHandler(enabled = uiState.pathHistory.size > 1) {
        viewModel.navigateUp()
    }

    // Show preview screen as overlay when previewObject is set
    if (uiState.previewObject != null) {
        // Handle back gesture to close preview
        BackHandler {
            viewModel.closePreview()
        }

        FilePreviewScreen(
            previewableObjects = uiState.previewableObjects,
            currentIndex = uiState.previewIndex,
            currentObject = uiState.previewObject!!,
            fileData = uiState.previewData,
            streamUrl = uiState.previewStreamUrl,
            isLoading = uiState.isPreviewLoading,
            error = uiState.previewError,
            onNavigateBack = { viewModel.closePreview() },
            onPageChanged = { newIndex -> viewModel.navigateToPreviewIndex(newIndex) },
            onDownload = { obj ->
                // For streaming media or large files, use background download
                if (uiState.previewStreamUrl != null || obj.size > 5 * 1024 * 1024) {
                    viewModel.downloadFileInBackground(obj)
                } else {
                    uiState.previewData?.let { data ->
                        showMessage(saveFile(obj.fileName, data))
                    }
                }
            },
            onDelete = { obj ->
                viewModel.showDeleteDialog(obj)
                viewModel.closePreview()
            },
            onShare = { obj ->
                viewModel.showShareDialog(obj)
            },
            onRename = { obj ->
                viewModel.showRenameDialog(obj)
            },
            onDetails = { obj ->
                viewModel.showDetailsDialog(obj)
            },
            onOpenWith = { obj, file ->
                openFileWithExternalApp(context, file, obj.mimeType)?.let { showMessage(it) }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            if (uiState.isSearchActive) {
                // Search mode top bar
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search files...") },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = { keyboardController?.hide() },
                                ),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    actions = {
                        // Recursive search toggle
                        IconButton(onClick = { viewModel.toggleSearchRecursive() }) {
                            Icon(
                                imageVector = if (uiState.isSearchRecursive) Icons.Default.FolderOff else Icons.Default.FolderOpen,
                                contentDescription = if (uiState.isSearchRecursive) "Search this folder only" else "Search recursively",
                                tint = if (uiState.isSearchRecursive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (uiState.isSearchingRecursive) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                )
            } else if (uiState.isMultiSelectMode) {
                // Multi-select mode top bar
                TopAppBar(
                    title = {
                        Text("${uiState.selectedObjects.size} selected")
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        // Select all
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        // Copy
                        IconButton(
                            onClick = { viewModel.copySelectedObjects() },
                            enabled = uiState.selectedObjects.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        // Cut
                        IconButton(
                            onClick = { viewModel.cutSelectedObjects() },
                            enabled = uiState.selectedObjects.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                        }
                        // Delete
                        IconButton(
                            onClick = { viewModel.showDeleteDialogForSelected() },
                            enabled = uiState.selectedObjects.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint =
                                    if (uiState.selectedObjects.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                            )
                        }
                    },
                )
            } else {
                // Normal mode top bar
                TopAppBar(
                    title = {
                        Column {
                            Text(bucketName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (uiState.currentPrefix.isNotEmpty()) {
                                Text(
                                    text = uiState.currentPrefix,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!viewModel.navigateUp()) {
                                    onNavigateBack()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Transfers indicator (only show when there are active transfers)
                        if (uiState.activeTransfers.isNotEmpty()) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text(uiState.activeTransfers.size.toString())
                                    }
                                },
                            ) {
                                IconButton(onClick = { showTransfersSheet = true }) {
                                    Icon(Icons.Default.SyncAlt, contentDescription = "Transfers")
                                }
                            }
                        }
                        // Search
                        IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        // Sort menu
                        var showSortMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                if (uiState.sortOption == option) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.size(18.dp))
                                                }
                                                Text(option.displayName)
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSortOption(option)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        // View mode toggle
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                imageVector =
                                    if (uiState.viewMode == ViewMode.LIST) {
                                        Icons.Default.GridView
                                    } else {
                                        Icons.AutoMirrored.Filled.ViewList
                                    },
                                contentDescription =
                                    if (uiState.viewMode == ViewMode.LIST) {
                                        "Switch to grid view"
                                    } else {
                                        "Switch to list view"
                                    },
                            )
                        }
                        // Toggle multi-select mode
                        IconButton(onClick = { viewModel.toggleMultiSelectMode() }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Multi-select")
                        }
                        // More options menu (storage stats, etc.)
                        var showMoreMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Storage stats") },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.showStorageStats()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.PieChart, contentDescription = null)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Paste FAB (shown when clipboard has items)
                if (uiState.clipboard != null && !uiState.isMultiSelectMode) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.paste() },
                        icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                        text = {
                            Text(
                                if (uiState.clipboard!!.action == ClipboardAction.MOVE) {
                                    "Move ${uiState.clipboard!!.objects.size}"
                                } else {
                                    "Paste ${uiState.clipboard!!.objects.size}"
                                },
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                // Create folder FAB
                if (!uiState.isMultiSelectMode) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.showCreateFolderDialog() },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
                    }
                }

                // Upload FAB
                if (!uiState.isMultiSelectMode) {
                    FloatingActionButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Upload files",
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pullRefresh(pullRefreshState),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Breadcrumb navigation
                if (uiState.pathHistory.size > 1 || uiState.currentPrefix.isNotEmpty()) {
                    BreadcrumbNavigation(
                        pathHistory = uiState.pathHistory,
                        bucketName = bucketName,
                        onNavigateToSegment = { viewModel.navigateToPathSegment(it) },
                    )
                    HorizontalDivider()
                }

                // Clipboard indicator
                if (uiState.clipboard != null) {
                    ClipboardIndicator(
                        clipboard = uiState.clipboard!!,
                        onClear = { viewModel.clearClipboard() },
                        modifier = Modifier.padding(8.dp),
                    )
                }

                if (displayObjects.isEmpty() && !uiState.isLoading) {
                    if (uiState.isSearchingRecursive) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Searching recursively...")
                            }
                        }
                    } else if (uiState.searchQuery.isNotEmpty()) {
                        NoSearchResultsView(query = uiState.searchQuery)
                    } else {
                        EmptyObjectsView()
                    }
                } else {
                    // List or Grid view
                    if (uiState.viewMode == ViewMode.LIST) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 88.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(displayObjects) { obj ->
                                // Load thumbnail URL for images and videos
                                var thumbnailUrl by remember(obj.key) { mutableStateOf<String?>(null) }
                                LaunchedEffect(obj.key) {
                                    if ((obj.fileType == FileType.IMAGE || obj.fileType == FileType.VIDEO) && !obj.isFolder) {
                                        thumbnailUrl = viewModel.getThumbnailUrl(obj)
                                    }
                                }

                                ObjectItem(
                                    obj = obj,
                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                    isSelected = obj in uiState.selectedObjects,
                                    thumbnailUrl = thumbnailUrl,
                                    onClick = {
                                        when {
                                            uiState.isMultiSelectMode -> {
                                                viewModel.toggleObjectSelection(obj)
                                            }

                                            obj.isFolder -> {
                                                viewModel.navigateToFolder(obj.key)
                                            }

                                            else -> {
                                                viewModel.openPreview(obj)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isMultiSelectMode) {
                                            viewModel.toggleMultiSelectMode()
                                            viewModel.toggleObjectSelection(obj)
                                        }
                                    },
                                    onDownload = {
                                        // Use background download for large files (>5MB)
                                        // This allows downloads to continue when app is backgrounded
                                        if (obj.size > 5 * 1024 * 1024) {
                                            viewModel.downloadFileInBackground(obj)
                                        } else {
                                            viewModel.downloadObject(obj) { data ->
                                                showMessage(saveFile(obj.fileName, data))
                                            }
                                        }
                                    },
                                    onOpenWith = {
                                        viewModel.downloadForOpenWith(obj) { file ->
                                            openFileWithExternalApp(context, file, obj.mimeType)?.let { showMessage(it) }
                                        }
                                    },
                                    onShare = { viewModel.showShareDialog(obj) },
                                    onRename = { viewModel.showRenameDialog(obj) },
                                    onDetails = { viewModel.showDetailsDialog(obj) },
                                    onDelete = { viewModel.showDeleteDialog(obj) },
                                    onDownloadFolder =
                                        if (obj.isFolder) {
                                            { viewModel.downloadFolderRecursively(obj) }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    } else {
                        // Grid view
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            state = gridState,
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 88.dp,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(displayObjects) { obj ->
                                var thumbnailUrl by remember(obj.key) { mutableStateOf<String?>(null) }
                                LaunchedEffect(obj.key) {
                                    if ((obj.fileType == FileType.IMAGE || obj.fileType == FileType.VIDEO) && !obj.isFolder) {
                                        thumbnailUrl = viewModel.getThumbnailUrl(obj)
                                    }
                                }

                                ObjectGridItem(
                                    obj = obj,
                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                    isSelected = obj in uiState.selectedObjects,
                                    thumbnailUrl = thumbnailUrl,
                                    onClick = {
                                        when {
                                            uiState.isMultiSelectMode -> {
                                                viewModel.toggleObjectSelection(obj)
                                            }

                                            obj.isFolder -> {
                                                viewModel.navigateToFolder(obj.key)
                                            }

                                            else -> {
                                                viewModel.openPreview(obj)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isMultiSelectMode) {
                                            viewModel.toggleMultiSelectMode()
                                            viewModel.toggleObjectSelection(obj)
                                        }
                                    },
                                    onDownload = {
                                        if (obj.size > 5 * 1024 * 1024) {
                                            viewModel.downloadFileInBackground(obj)
                                        } else {
                                            viewModel.downloadObject(obj) { data ->
                                                showMessage(saveFile(obj.fileName, data))
                                            }
                                        }
                                    },
                                    onOpenWith = {
                                        viewModel.downloadForOpenWith(obj) { file ->
                                            openFileWithExternalApp(context, file, obj.mimeType)?.let { showMessage(it) }
                                        }
                                    },
                                    onShare = { viewModel.showShareDialog(obj) },
                                    onRename = { viewModel.showRenameDialog(obj) },
                                    onDetails = { viewModel.showDetailsDialog(obj) },
                                    onDelete = { viewModel.showDeleteDialog(obj) },
                                    onDownloadFolder =
                                        if (obj.isFolder) {
                                            { viewModel.downloadFolderRecursively(obj) }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = uiState.isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // Upload progress overlay
            if (uiState.isUploading) {
                ProgressOverlay(
                    message = uiState.uploadProgress,
                    progress = uiState.uploadProgressPercent,
                    canCancel = uiState.canCancel,
                    onCancel = { viewModel.cancelCurrentOperation() },
                )
            }

            // Download progress overlay
            if (uiState.isDownloading) {
                ProgressOverlay(
                    message = uiState.downloadProgress,
                    progress = uiState.downloadProgressPercent,
                    canCancel = uiState.canCancel,
                    onCancel = { viewModel.cancelCurrentOperation() },
                )
            }

            // Paste progress overlay
            if (uiState.isPasting) {
                ProgressOverlay(
                    message = uiState.pasteProgress,
                    progress = null,
                    canCancel = false,
                    onCancel = {},
                )
            }
        }
    }

    // File Details Dialog
    if (uiState.showDetailsDialog && uiState.detailsObject != null) {
        FileDetailsDialog(
            obj = uiState.detailsObject!!,
            onDismiss = { viewModel.hideDetailsDialog() },
        )
    }

    // Delete Object Dialog (single or multi)
    if (uiState.showDeleteDialog) {
        if (uiState.isMultiSelectMode && uiState.selectedObjects.isNotEmpty()) {
            DeleteMultipleObjectsDialog(
                count = uiState.selectedObjects.size,
                onConfirm = viewModel::deleteObject,
                onDismiss = viewModel::hideDeleteDialog,
                isDeleting = uiState.isDeleting,
            )
        } else if (uiState.selectedObject != null) {
            // Check if it's a folder - use recursive delete dialog
            if (uiState.selectedObject!!.isFolder) {
                DeleteFolderDialog(
                    folderName = uiState.selectedObject!!.fileName,
                    isDeleting = uiState.isDeletingFolder,
                    deleteProgress = uiState.folderDeleteProgress,
                    onConfirm = { viewModel.deleteFolderRecursively(uiState.selectedObject!!) },
                    onDismiss = viewModel::hideDeleteDialog,
                )
            } else {
                DeleteObjectDialog(
                    objectKey = uiState.selectedObject!!.key,
                    onConfirm = viewModel::deleteObject,
                    onDismiss = viewModel::hideDeleteDialog,
                    isDeleting = uiState.isDeleting,
                )
            }
        }
    }

    // Create Folder Dialog
    if (uiState.showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { folderName -> viewModel.createFolder(folderName) },
            onDismiss = { viewModel.hideCreateFolderDialog() },
            isCreating = uiState.isCreatingFolder,
        )
    }

    // Rename Dialog
    if (uiState.showRenameDialog && uiState.renameObject != null) {
        RenameDialog(
            currentName = uiState.renameObject!!.fileName,
            onConfirm = { newName -> viewModel.renameObject(newName) },
            onDismiss = { viewModel.hideRenameDialog() },
            isRenaming = uiState.isRenaming,
        )
    }

    // Share Link Dialog
    if (uiState.showShareDialog && uiState.shareObject != null) {
        ShareLinkDialog(
            obj = uiState.shareObject!!,
            shareUrl = uiState.shareUrl,
            isGenerating = uiState.isGeneratingShareUrl,
            onGenerateUrl = { expiration -> viewModel.generateShareUrl(expiration) },
            onDismiss = { viewModel.hideShareDialog() },
            onShowMessage = showMessage,
        )
    }

    // Storage Stats Dialog
    if (uiState.showStorageStats) {
        StorageStatsDialog(
            stats = uiState.storageStats,
            isLoading = uiState.isLoadingStats,
            currentPrefix = uiState.currentPrefix,
            onDismiss = { viewModel.hideStorageStats() },
        )
    }

    // Folder Delete Progress Overlay
    if (uiState.isDeletingFolder) {
        ProgressOverlay(
            message = uiState.folderDeleteProgress,
            progress = null,
            canCancel = false,
            onCancel = {},
        )
    }

    // Folder Download Progress Overlay
    if (uiState.isDownloadingFolder) {
        ProgressOverlay(
            message = uiState.folderDownloadProgress,
            progress = null,
            canCancel = false,
            onCancel = {},
        )
    }

    // Transfers Bottom Sheet
    if (showTransfersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTransfersSheet = false },
            sheetState = transfersSheetState,
        ) {
            TransfersSheetContent(
                activeTransfers = uiState.activeTransfers,
                onCancelTransfer = { transferId -> viewModel.cancelTransfer(transferId) },
                onCancelAll = { viewModel.cancelBackgroundTransfers() },
            )
        }
    }
}

@Composable
private fun ClipboardIndicator(
    clipboard: com.imaviso.stash.ui.viewmodel.ClipboardData,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector =
                        if (clipboard.action == ClipboardAction.MOVE) {
                            Icons.Default.ContentCut
                        } else {
                            Icons.Default.ContentCopy
                        },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "${clipboard.objects.size} item(s) ${if (clipboard.action == ClipboardAction.MOVE) "to move" else "copied"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = onClear,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear clipboard",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyObjectsView() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Objects",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap + to upload a file",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObjectItem(
    obj: S3Object,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onDownloadFolder: (() -> Unit)? = null,
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    // Color contrast fix: use proper colors based on selection state
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val secondaryContentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox for multi-select (files and folders)
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumbnail or icon
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnailUrl != null && (obj.fileType == FileType.IMAGE || obj.fileType == FileType.VIDEO)) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(LocalContext.current)
                                        .data(thumbnailUrl)
                                        .crossfade(true)
                                        .size(96) // Request small size for thumbnail
                                        .build(),
                                contentDescription = "Thumbnail",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            // Play icon overlay for videos
                            if (obj.fileType == FileType.VIDEO) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Icon(
                            imageVector = getFileTypeIcon(obj),
                            contentDescription = null,
                            tint = getFileTypeIconColor(obj),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = obj.fileName.ifEmpty { obj.key },
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!obj.isFolder) {
                            Text(
                                text = obj.formattedSize,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryContentColor,
                            )
                        }
                        obj.lastModified?.let { date ->
                            Text(
                                text = dateFormat.format(date),
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryContentColor,
                            )
                        }
                    }
                }
            }

            // Action buttons (only shown when not in multi-select mode)
            if (!isMultiSelectMode) {
                // More options menu
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = secondaryContentColor,
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (obj.isFolder) {
                            // Folder-specific actions
                            if (onDownloadFolder != null) {
                                DropdownMenuItem(
                                    text = { Text("Download folder") },
                                    onClick = {
                                        showMenu = false
                                        onDownloadFolder()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderZip, contentDescription = null)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Details") },
                                onClick = {
                                    showMenu = false
                                    onDetails()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete folder") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        } else {
                            // File-specific actions
                            DropdownMenuItem(
                                text = { Text("Share link") },
                                onClick = {
                                    showMenu = false
                                    onShare()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open with") },
                                onClick = {
                                    showMenu = false
                                    onOpenWith()
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownload()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showMenu = false
                                    onRename()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Details") },
                                onClick = {
                                    showMenu = false
                                    onDetails()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getFileTypeIcon(obj: S3Object) =
    when {
        obj.isFolder -> {
            Icons.Default.Folder
        }

        else -> {
            when (obj.fileType) {
                FileType.IMAGE -> Icons.Default.Image
                FileType.VIDEO -> Icons.Default.VideoFile
                FileType.AUDIO -> Icons.Default.AudioFile
                FileType.TEXT -> Icons.Default.Description
                FileType.PDF -> Icons.Default.PictureAsPdf
                FileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
            }
        }
    }

@Composable
private fun getFileTypeIconColor(obj: S3Object) =
    when {
        obj.isFolder -> {
            MaterialTheme.colorScheme.primary
        }

        else -> {
            when (obj.fileType) {
                FileType.IMAGE -> MaterialTheme.colorScheme.tertiary
                FileType.VIDEO -> MaterialTheme.colorScheme.error
                FileType.AUDIO -> MaterialTheme.colorScheme.secondary
                FileType.PDF -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    }

@Composable
private fun ProgressOverlay(
    message: String,
    progress: Float? = null,
    canCancel: Boolean = false,
    onCancel: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (progress != null && progress > 0f) {
                        // Determinate progress
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Indeterminate progress
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (canCancel) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteObjectDialog(
    objectKey: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDeleting: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Object") },
        text = {
            Text("Are you sure you want to delete '$objectKey'? This action cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text("Delete")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeleteMultipleObjectsDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDeleting: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count Objects") },
        text = {
            Text("Are you sure you want to delete $count selected objects? This action cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text("Delete All")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    isCreating: Boolean,
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName) },
                enabled = !isCreating && folderName.isNotBlank(),
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    isRenaming: Boolean,
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newName) },
                enabled = !isRenaming && newName.isNotBlank() && newName != currentName,
            ) {
                if (isRenaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Rename")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NoSearchResultsView(query: String) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Results",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No files match \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BreadcrumbNavigation(
    pathHistory: List<String>,
    bucketName: String,
    onNavigateToSegment: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Root/bucket
        TextButton(
            onClick = { onNavigateToSegment(0) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Default.Home,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(bucketName, style = MaterialTheme.typography.bodyMedium)
        }

        // Path segments
        pathHistory.drop(1).forEachIndexed { index, path ->
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val segmentName = path.trimEnd('/').substringAfterLast('/')
            val isLast = index == pathHistory.size - 2

            TextButton(
                onClick = { onNavigateToSegment(index + 1) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = segmentName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLast) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    color =
                        if (isLast) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    }

    // Auto-scroll to end when path changes
    LaunchedEffect(pathHistory) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
}

@Composable
private fun FileDetailsDialog(
    obj: S3Object,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "File Details",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailRow("Name", obj.fileName)
                DetailRow("Full Path", obj.key)
                DetailRow("Size", obj.formattedSize + " (${obj.size} bytes)")
                obj.lastModified?.let {
                    DetailRow("Last Modified", dateFormat.format(it))
                }
                obj.etag?.let {
                    DetailRow("ETag", it.replace("\"", ""))
                }
                obj.storageClass?.let {
                    DetailRow("Storage Class", it)
                }
                DetailRow("MIME Type", obj.mimeType)
                DetailRow("File Type", obj.fileType.name)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObjectGridItem(
    obj: S3Object,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit = {},
    onOpenWith: () -> Unit = {},
    onShare: () -> Unit = {},
    onRename: () -> Unit = {},
    onDetails: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDownloadFolder: (() -> Unit)? = null,
) {
    // Color contrast fix: use proper colors based on selection state
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val secondaryContentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Thumbnail or icon
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnailUrl != null && (obj.fileType == FileType.IMAGE || obj.fileType == FileType.VIDEO)) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(LocalContext.current)
                                        .data(thumbnailUrl)
                                        .crossfade(true)
                                        .size(200) // Request size for grid thumbnail
                                        .build(),
                                contentDescription = "Thumbnail",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            // Play icon overlay for videos
                            if (obj.fileType == FileType.VIDEO) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Icon(
                            imageVector = getFileTypeIcon(obj),
                            contentDescription = null,
                            tint = getFileTypeIconColor(obj),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = obj.fileName.ifEmpty { obj.key },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                if (!obj.isFolder) {
                    Text(
                        text = obj.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContentColor,
                    )
                }
            }

            // Checkbox overlay for multi-select
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                )
            } else {
                // Overflow menu (parity with list view)
                var showMenu by remember { mutableStateOf(false) }
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp),
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (obj.isFolder) {
                            if (onDownloadFolder != null) {
                                DropdownMenuItem(
                                    text = { Text("Download folder") },
                                    onClick = {
                                        showMenu = false
                                        onDownloadFolder()
                                    },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Details") },
                                onClick = {
                                    showMenu = false
                                    onDetails()
                                },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete folder") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Share link") },
                                onClick = {
                                    showMenu = false
                                    onShare()
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Open with") },
                                onClick = {
                                    showMenu = false
                                    onOpenWith()
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownload()
                                },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showMenu = false
                                    onRename()
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Details") },
                                onClick = {
                                    showMenu = false
                                    onDetails()
                                },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun handleFileUpload(
    context: Context,
    uri: Uri,
    viewModel: ObjectsViewModel,
): String? {
    return try {
        val contentResolver = context.contentResolver
        val fileName = getFileName(contentResolver, uri) ?: "unknown"
        val contentType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileSize = getFileSize(contentResolver, uri)

        // Use background upload for all files via WorkManager
        // This allows uploads to continue even when app is backgrounded
        if (fileSize > 5 * 1024 * 1024) {
            // Large files - use WorkManager background upload
            viewModel.uploadFileInBackground(uri, fileName, contentType)
        } else {
            // For small files, read into memory (faster)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val data = inputStream.readBytes()
                viewModel.uploadFile(fileName, data, contentType)
            }
        }
        null
    } catch (e: Exception) {
        "Failed to read file: ${e.message}"
    }
}

private fun getFileSize(
    contentResolver: ContentResolver,
    uri: Uri,
): Long {
    var size: Long = 0
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (sizeIndex != -1 && cursor.moveToFirst()) {
            size = cursor.getLong(sizeIndex)
        }
    }
    return size
}

private fun getFileName(
    contentResolver: ContentResolver,
    uri: Uri,
): String? {
    var name: String? = null
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        name = cursor.getString(nameIndex)
    }
    return name
}

/**
 * Save [data] to the public Downloads directory as [fileName].
 * Returns a user-facing message (success or failure).
 */
private fun saveFile(
    fileName: String,
    data: ByteArray,
): String =
    try {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)
        FileOutputStream(file).use { it.write(data) }
        "Saved to Downloads: $fileName"
    } catch (e: Exception) {
        "Failed to save: ${e.message}"
    }

/**
 * Open [file] with an external app via ACTION_VIEW chooser.
 * Returns null on success or a failure message.
 */
private fun openFileWithExternalApp(
    context: Context,
    file: File,
    mimeType: String,
): String? {
    return try {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        val chooser =
            Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(chooser)
        null
    } catch (e: Exception) {
        "Failed to open file: ${e.message}"
    }
}

@Composable
private fun TransfersSheetContent(
    activeTransfers: List<TransferInfo>,
    onCancelTransfer: (String) -> Unit,
    onCancelAll: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
    ) {
        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Transfers (${activeTransfers.size})",
                style = MaterialTheme.typography.titleLarge,
            )
            if (activeTransfers.isNotEmpty()) {
                TextButton(onClick = onCancelAll) {
                    Text("Cancel All")
                }
            }
        }

        HorizontalDivider()

        if (activeTransfers.isEmpty()) {
            // Empty state
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No Active Transfers",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(activeTransfers, key = { it.id }) { transfer ->
                    TransferItemCard(
                        transfer = transfer,
                        onCancel = { onCancelTransfer(transfer.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferItemCard(
    transfer: TransferInfo,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector =
                            if (transfer.type == TransferType.UPLOAD) {
                                Icons.Default.Upload
                            } else {
                                Icons.Default.Download
                            },
                        contentDescription = null,
                        tint =
                            if (transfer.error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transfer.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = transfer.status,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (transfer.error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }

                // Cancel button
                if (transfer.error == null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Progress bar
            if (transfer.error == null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { transfer.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Size info
                if (transfer.totalBytes > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatTransferBytes(transfer.bytesTransferred),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${transfer.progress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTransferBytes(transfer.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Error message
            transfer.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatTransferBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        else -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    }

// ==================== SHARE DIALOG ====================

@Composable
private fun ShareLinkDialog(
    obj: S3Object,
    shareUrl: String?,
    isGenerating: Boolean,
    onGenerateUrl: (ShareExpiration) -> Unit,
    onDismiss: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var selectedExpiration by remember { mutableStateOf(ShareExpiration.ONE_DAY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Link") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = obj.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )

                Text(
                    text = "Link expires after:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Expiration options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShareExpiration.entries.take(2).forEach { expiration ->
                        FilterChip(
                            selected = selectedExpiration == expiration,
                            onClick = { selectedExpiration = expiration },
                            label = { Text(expiration.displayName) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShareExpiration.entries.drop(2).forEach { expiration ->
                        FilterChip(
                            selected = selectedExpiration == expiration,
                            onClick = { selectedExpiration = expiration },
                            label = { Text(expiration.displayName) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (shareUrl != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Text(
                                text = shareUrl,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(shareUrl))
                                        onShowMessage("Link copied!")
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy")
                                }
                                Button(
                                    onClick = {
                                        val shareIntent =
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareUrl)
                                                putExtra(Intent.EXTRA_SUBJECT, obj.fileName)
                                            }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Share")
                                }
                            }
                        }
                    }
                }

                if (isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generating link...")
                    }
                }
            }
        },
        confirmButton = {
            if (shareUrl == null) {
                Button(
                    onClick = { onGenerateUrl(selectedExpiration) },
                    enabled = !isGenerating,
                ) {
                    Text("Generate Link")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        },
        dismissButton = {
            if (shareUrl == null) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

// ==================== STORAGE STATS DIALOG ====================

@Composable
private fun StorageStatsDialog(
    stats: StorageStats?,
    isLoading: Boolean,
    currentPrefix: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (currentPrefix.isEmpty()) "Bucket Storage" else "Folder Storage",
            )
        },
        text = {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Calculating storage...")
                }
            } else if (stats != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Summary card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stats.formattedTotalSize,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "${stats.fileCount} files, ${stats.folderCount} folders",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }

                    // Breakdown by file type
                    if (stats.byFileType.isNotEmpty()) {
                        Text(
                            text = "By File Type",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        stats.byFileType
                            .toList()
                            .sortedByDescending { it.second.totalSize }
                            .forEach { (fileType, typeStats) ->
                                FileTypeStatRow(fileType, typeStats, stats.totalSize)
                            }
                    }
                }
            } else {
                Text("No data available")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun FileTypeStatRow(
    fileType: FileType,
    stats: FileTypeStats,
    totalSize: Long,
) {
    val percentage = if (totalSize > 0) (stats.totalSize.toFloat() / totalSize * 100) else 0f
    val icon =
        when (fileType) {
            FileType.IMAGE -> Icons.Default.Image
            FileType.VIDEO -> Icons.Default.VideoFile
            FileType.AUDIO -> Icons.Default.AudioFile
            FileType.TEXT -> Icons.Default.Description
            FileType.PDF -> Icons.Default.PictureAsPdf
            FileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    val color =
        when (fileType) {
            FileType.IMAGE -> Color(0xFF4CAF50)
            FileType.VIDEO -> Color(0xFFF44336)
            FileType.AUDIO -> Color(0xFF9C27B0)
            FileType.TEXT -> Color(0xFF2196F3)
            FileType.PDF -> Color(0xFFFF5722)
            FileType.OTHER -> Color(0xFF607D8B)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = fileType.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${stats.count} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { percentage / 100f },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                color = color,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${stats.formattedSize} (${String.format("%.1f", percentage)}%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==================== DELETE FOLDER DIALOG ====================

@Composable
private fun DeleteFolderDialog(
    folderName: String,
    isDeleting: Boolean,
    deleteProgress: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text("Delete Folder") },
        text = {
            Column {
                if (isDeleting) {
                    Text(deleteProgress)
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        "Are you sure you want to delete '$folderName' and ALL its contents? " +
                            "This action cannot be undone.",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "This will delete all files and subfolders!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text("Delete All")
                }
            }
        },
        dismissButton = {
            if (!isDeleting) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
