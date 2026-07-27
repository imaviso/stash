package com.imaviso.stash.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imaviso.stash.data.repository.TransferInfo
import com.imaviso.stash.data.repository.TransferState
import com.imaviso.stash.data.repository.TransferType
import com.imaviso.stash.ui.viewmodel.TransfersViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onNavigateBack: () -> Unit,
    viewModel: TransfersViewModel = viewModel(),
) {
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val historyTransfers by viewModel.historyTransfers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (activeTransfers.isNotEmpty()) {
                        TextButton(onClick = viewModel::cancelAll) {
                            Text("Cancel All")
                        }
                    } else if (historyTransfers.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearHistory) {
                            Text("Clear History")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (activeTransfers.isEmpty() && historyTransfers.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No Transfers",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Uploads and downloads will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (activeTransfers.isNotEmpty()) {
                    item {
                        SectionHeader("Active (${activeTransfers.size})")
                    }
                    items(activeTransfers, key = { it.id }) { transfer ->
                        TransferItem(
                            transfer = transfer,
                            onCancel = { viewModel.cancelTransfer(transfer.id) },
                        )
                    }
                }

                if (historyTransfers.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader("History")
                    }
                    items(historyTransfers, key = { it.id }) { transfer ->
                        TransferItem(
                            transfer = transfer,
                            onCancel = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun TransferItem(
    transfer: TransferInfo,
    onCancel: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (transfer.state) {
                        TransferState.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                        TransferState.FAILED -> MaterialTheme.colorScheme.errorContainer
                        TransferState.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    },
            ),
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
                            when (transfer.state) {
                                TransferState.FAILED -> MaterialTheme.colorScheme.error
                                TransferState.COMPLETED -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = transfer.status.ifBlank { transfer.state.name.lowercase().replaceFirstChar { it.uppercase() } },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (transfer.error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        if (transfer.state != TransferState.ACTIVE) {
                            Text(
                                text = remember(transfer.timestamp) {
                                    SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(transfer.timestamp))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (onCancel != null && transfer.error == null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Progress bar (only for active transfers with meaningful progress)
            if (transfer.state == TransferState.ACTIVE) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { transfer.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (transfer.totalBytes > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatBytes(transfer.bytesTransferred),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${transfer.progress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatBytes(transfer.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

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

private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        else -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    }
