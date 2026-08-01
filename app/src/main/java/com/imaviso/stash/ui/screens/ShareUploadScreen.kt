package com.imaviso.stash.ui.screens

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.data.model.SharedFileInfo
import com.imaviso.stash.ui.components.BreadcrumbNavigation
import com.imaviso.stash.ui.components.CreateFolderDialog
import com.imaviso.stash.ui.viewmodel.ShareUploadViewModel
import com.imaviso.stash.util.FormatUtils

/**
 * Screen for uploading files shared from other apps.
 * Allows browsing folders and creating new folders before uploading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareUploadScreen(
    sharedUris: List<Uri>,
    onUploadComplete: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ShareUploadViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Extract file info from URIs
    val sharedFiles =
        remember(sharedUris) {
            sharedUris.mapNotNull { uri ->
                getFileInfo(context.contentResolver, uri)
            }
        }

    // Handle back navigation within folders
    BackHandler(enabled = uiState.pathHistory.size > 1) {
        viewModel.navigateUp()
    }

    // Show error if no files
    LaunchedEffect(sharedFiles) {
        if (sharedFiles.isEmpty() && sharedUris.isNotEmpty()) {
            Toast.makeText(context, "Could not read shared files", Toast.LENGTH_SHORT).show()
            onCancel()
        } else if (sharedUris.isEmpty()) {
            Toast.makeText(context, "No files to upload", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    // Load accounts and buckets on start
    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    // Handle errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Handle upload complete
    LaunchedEffect(uiState.uploadComplete) {
        if (uiState.uploadComplete) {
            Toast
                .makeText(
                    context,
                    "Uploaded ${sharedFiles.size} file(s) successfully!",
                    Toast.LENGTH_SHORT,
                ).show()
            onUploadComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Upload to S3")
                        if (uiState.selectedBucket != null) {
                            Text(
                                text = uiState.selectedBucket!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.navigateUp()) {
                            onCancel()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Create folder button
                    if (uiState.selectedBucket != null) {
                        IconButton(onClick = { viewModel.showCreateFolderDialog() }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Upload button at bottom
            if (uiState.hasValidConfig && uiState.selectedBucket != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        // Current path indicator
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Upload to: /${uiState.currentPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                // Upload runs in the ViewModel's viewModelScope
                                viewModel.uploadFiles(sharedFiles)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isUploading,
                        ) {
                            if (uiState.isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(uiState.uploadProgress)
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Upload ${sharedFiles.size} file(s) here")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (!uiState.hasValidConfig) {
                // No S3 account configured
                NoAccountConfigured(onCancel = onCancel)
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Files to upload section (collapsible)
                    var filesExpanded by remember { mutableStateOf(true) }

                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { filesExpanded = !filesExpanded }
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${sharedFiles.size} file(s) to upload",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                Icon(
                                    if (filesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (filesExpanded) "Collapse" else "Expand",
                                )
                            }

                            if (filesExpanded) {
                                HorizontalDivider()
                                sharedFiles.forEach { file ->
                                    SharedFileItem(file)
                                }
                            }
                        }
                    }

                    // Bucket selection (if not selected or want to change)
                    if (uiState.buckets.size > 1) {
                        var bucketExpanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = bucketExpanded,
                            onExpandedChange = { bucketExpanded = it },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            OutlinedTextField(
                                value = uiState.selectedBucket ?: "Select a bucket",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Bucket") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = bucketExpanded)
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            )

                            ExposedDropdownMenu(
                                expanded = bucketExpanded,
                                onDismissRequest = { bucketExpanded = false },
                            ) {
                                uiState.buckets.forEach { bucket ->
                                    DropdownMenuItem(
                                        text = { Text(bucket) },
                                        onClick = {
                                            viewModel.selectBucket(bucket)
                                            bucketExpanded = false
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Storage, contentDescription = null)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Breadcrumb navigation
                    if (uiState.pathHistory.isNotEmpty()) {
                        BreadcrumbNavigation(
                            pathHistory = uiState.pathHistory,
                            rootLabel = "/",
                            onNavigateToSegment = { index ->
                                if (index == 0) {
                                    viewModel.navigateToRoot()
                                } else {
                                    // Navigate to specific path in history
                                    repeat(uiState.pathHistory.size - index - 1) {
                                        viewModel.navigateUp()
                                    }
                                }
                            },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Folder browser
                    if (uiState.isLoadingFolders) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (uiState.folders.isEmpty()) {
                        // Empty folder
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No subfolders",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Files will be uploaded here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { viewModel.showCreateFolderDialog() }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create folder")
                            }
                        }
                    } else {
                        // Folder list
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            items(uiState.folders, key = { it.key }) { folder ->
                                FolderItem(
                                    folder = folder,
                                    onClick = { viewModel.navigateToFolder(folder.key) },
                                )
                            }
                        }
                    }
                }
            }

            // Upload progress overlay
            if (uiState.isUploading) {
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
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = uiState.uploadProgress,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
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
}

@Composable
private fun FolderItem(
    folder: S3Object,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = folder.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open folder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SharedFileItem(file: SharedFileInfo) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = getFileIcon(file.mimeType),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = FormatUtils.formatBytes(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoAccountConfigured(onCancel: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No S3 Account",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Please open Stash and configure an S3 account first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onCancel) {
            Text("OK")
        }
    }
}

private fun getFileIcon(mimeType: String) =
    when {
        mimeType.startsWith("image/") -> Icons.Default.Image
        mimeType.startsWith("video/") -> Icons.Default.VideoFile
        mimeType.startsWith("audio/") -> Icons.Default.AudioFile
        mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
        mimeType.startsWith("text/") -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

private fun getFileInfo(
    contentResolver: ContentResolver,
    uri: Uri,
): SharedFileInfo? =
    try {
        var fileName = "unknown"
        var size: Long = 0

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: "unknown"
                }
                if (sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        SharedFileInfo(
            uri = uri,
            fileName = fileName,
            mimeType = mimeType,
            size = size,
        )
    } catch (e: Exception) {
        null
    }
