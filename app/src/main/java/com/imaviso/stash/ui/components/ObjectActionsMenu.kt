package com.imaviso.stash.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Overflow actions menu shared by the list and grid object items.
 * Folder objects get Download folder / Details / Delete folder;
 * files get Share link / Open with / Download / Rename / Details / Delete.
 */
@Composable
fun ObjectActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isFolder: Boolean,
    onDownload: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onDownloadFolder: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (isFolder) {
            // Folder-specific actions
            if (onDownloadFolder != null) {
                DropdownMenuItem(
                    text = { Text("Download folder") },
                    onClick = {
                        onDismiss()
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
                    onDismiss()
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
                    onDismiss()
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
                    onDismiss()
                    onShare()
                },
                leadingIcon = {
                    Icon(Icons.Default.Share, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Open with") },
                onClick = {
                    onDismiss()
                    onOpenWith()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Download") },
                onClick = {
                    onDismiss()
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
                    onDismiss()
                    onRename()
                },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Details") },
                onClick = {
                    onDismiss()
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
                    onDismiss()
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
