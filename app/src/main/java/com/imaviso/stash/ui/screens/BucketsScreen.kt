package com.imaviso.stash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imaviso.stash.data.model.S3Account
import com.imaviso.stash.data.model.S3Bucket
import com.imaviso.stash.ui.viewmodel.BucketSortOption
import com.imaviso.stash.ui.viewmodel.BucketsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun BucketsScreen(
    onNavigateToConfig: () -> Unit,
    onNavigateToBucket: (String) -> Unit,
    onNavigateToTransfers: () -> Unit,
    viewModel: BucketsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val activeCountState =
        com.imaviso.stash.data.repository.TransferRepository.transfers.collectAsState()
    val activeCount = activeCountState.value.count { it.state == com.imaviso.stash.data.repository.TransferState.ACTIVE }

    val pullRefreshState =
        rememberPullRefreshState(
            refreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
        )

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("S3 Buckets")
                        uiState.activeAccount?.let { account ->
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .clickable { viewModel.showAccountPicker() },
                            )
                        }
                    }
                },
                actions = {
                    // Transfers entry point with active-count badge
                    BadgedBox(
                        badge = {
                            if (activeCount > 0) {
                                Badge { Text(activeCount.toString()) }
                            }
                        },
                    ) {
                        IconButton(onClick = onNavigateToTransfers) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Transfers")
                        }
                    }
                    // Bucket sort menu
                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            BucketSortOption.entries.forEach { option ->
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
                    IconButton(onClick = onNavigateToConfig) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.isConfigured) {
                FloatingActionButton(onClick = viewModel::showCreateDialog) {
                    Icon(Icons.Default.Add, contentDescription = "Create Bucket")
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
            when {
                !uiState.isConfigured -> {
                    NotConfiguredView(onNavigateToConfig)
                }

                uiState.buckets.isEmpty() && !uiState.isLoading -> {
                    EmptyBucketsView()
                }

                else -> {
                    val sortedBuckets =
                        remember(uiState.buckets, uiState.sortOption) {
                            viewModel.sortBuckets(uiState.buckets)
                        }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sortedBuckets, key = { it.name }) { bucket ->
                            BucketItem(
                                bucket = bucket,
                                onClick = { onNavigateToBucket(bucket.name) },
                                onDelete = { viewModel.showDeleteDialog(bucket) },
                                onShowStats = { viewModel.showBucketStats(bucket) },
                            )
                        }
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = uiState.isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    // Create Bucket Dialog
    if (uiState.showCreateDialog) {
        CreateBucketDialog(
            bucketName = uiState.newBucketName,
            onBucketNameChange = viewModel::updateNewBucketName,
            onConfirm = viewModel::createBucket,
            onDismiss = viewModel::hideCreateDialog,
            isCreating = uiState.isCreating,
        )
    }

    // Delete Bucket Dialog
    if (uiState.showDeleteDialog && uiState.selectedBucket != null) {
        DeleteBucketDialog(
            bucketName = uiState.selectedBucket!!.name,
            onConfirm = viewModel::deleteBucket,
            onDismiss = viewModel::hideDeleteDialog,
            isDeleting = uiState.isDeleting,
        )
    }

    // Account Picker Dialog
    if (uiState.showAccountPicker) {
        AccountPickerDialog(
            accounts = uiState.accounts,
            activeAccountId = uiState.activeAccount?.id,
            onSelectAccount = viewModel::switchAccount,
            onDismiss = viewModel::hideAccountPicker,
        )
    }

    // Bucket Stats Dialog
    if (uiState.showStatsDialog && uiState.statsBucket != null) {
        BucketStatsDialog(
            bucketName = uiState.statsBucket!!.name,
            stats = uiState.bucketStats,
            isLoading = uiState.isLoadingStats,
            onDismiss = viewModel::hideBucketStats,
        )
    }
}

@Composable
private fun BucketStatsDialog(
    bucketName: String,
    stats: com.imaviso.stash.ui.viewmodel.BucketStats?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bucket Stats") },
        text = {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning $bucketName...")
                }
            } else if (stats != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                                text = stats.formattedSize,
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
private fun AccountPickerDialog(
    accounts: List<S3Account>,
    activeAccountId: String?,
    onSelectAccount: (S3Account) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Account") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(accounts) { account ->
                    val isActive = account.id == activeAccountId
                    val contentColor =
                        if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    val secondaryContentColor =
                        if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }

                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectAccount(account) },
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (isActive) {
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
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isActive) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Active",
                                    tint = contentColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = secondaryContentColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = account.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor,
                                )
                                Text(
                                    text = account.endpoint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NotConfiguredView(onNavigateToConfig: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "S3 Not Configured",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Please configure your S3 credentials to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNavigateToConfig) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Configure")
        }
    }
}

@Composable
private fun EmptyBucketsView() {
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
            text = "No Buckets",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap + to create your first bucket",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BucketItem(
    bucket: S3Bucket,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShowStats: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = bucket.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    bucket.creationDate?.let { date ->
                        Text(
                            text = "Created ${dateFormat.format(date)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Overflow: stats + delete
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Bucket stats") },
                        onClick = {
                            showMenu = false
                            onShowStats()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.PieChart, contentDescription = null)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete bucket") },
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

@Composable
private fun CreateBucketDialog(
    bucketName: String,
    onBucketNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isCreating: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Bucket") },
        text = {
            OutlinedTextField(
                value = bucketName,
                onValueChange = onBucketNameChange,
                label = { Text("Bucket Name") },
                placeholder = { Text("my-bucket") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = bucketName.isNotBlank() && !isCreating,
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
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
private fun DeleteBucketDialog(
    bucketName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDeleting: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Bucket") },
        text = {
            Text("Are you sure you want to delete '$bucketName'? This action cannot be undone. The bucket must be empty.")
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
