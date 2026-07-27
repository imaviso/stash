package com.imaviso.stash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imaviso.stash.data.model.S3Account
import com.imaviso.stash.ui.viewmodel.ConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConfigViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // Show account edit screen when editing
    if (uiState.isEditing && uiState.editingAccount != null) {
        AccountEditScreen(
            account = uiState.editingAccount!!,
            isSaving = uiState.isSaving,
            isTesting = uiState.isTesting,
            testResult = uiState.testResult,
            testSuccess = uiState.testSuccess,
            onNameChange = viewModel::updateEditingAccountName,
            onEndpointChange = viewModel::updateEditingAccountEndpoint,
            onAccessKeyChange = viewModel::updateEditingAccountAccessKey,
            onSecretKeyChange = viewModel::updateEditingAccountSecretKey,
            onRegionChange = viewModel::updateEditingAccountRegion,
            onUsePathStyleChange = viewModel::updateEditingAccountUsePathStyle,
            onTestConnection = viewModel::testConnection,
            onClearTestResult = viewModel::clearTestResult,
            onSave = viewModel::saveEditingAccount,
            onCancel = viewModel::cancelEditing,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("S3 Accounts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.startNewAccount() }) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.accounts.isEmpty()) {
            // Empty state
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
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
                    text = "No Accounts",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap + to add an S3 account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = "Select an account to use or manage your S3 connections",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                items(uiState.accounts) { account ->
                    AccountItem(
                        account = account,
                        isActive = account.id == uiState.activeAccountId,
                        onClick = { viewModel.setActiveAccount(account) },
                        onEdit = { viewModel.editAccount(account) },
                        onDelete = { viewModel.showDeleteAccountDialog(account) },
                    )
                }

                // Security section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Security",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "App lock",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Require biometric authentication on launch",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = uiState.appLockEnabled,
                                onCheckedChange = viewModel::setAppLockEnabled,
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog && uiState.accountToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.hideDeleteAccountDialog() },
            title = { Text("Delete Account") },
            text = {
                Text("Are you sure you want to delete \"${uiState.accountToDelete!!.name}\"? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteAccount() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideDeleteAccountDialog() }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountItem(
    account: S3Account,
    isActive: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
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
                .clickable(onClick = onClick),
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
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Active indicator
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditScreen(
    account: S3Account,
    isSaving: Boolean,
    isTesting: Boolean = false,
    testResult: String? = null,
    testSuccess: Boolean = false,
    onNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onSecretKeyChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onUsePathStyleChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onClearTestResult: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var showSecretKey by remember { mutableStateOf(false) }
    val isNewAccount = account.name.isEmpty() && account.endpoint.isEmpty()

    // Clear test result when any field changes
    LaunchedEffect(account.endpoint, account.accessKey, account.secretKey, account.region) {
        onClearTestResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNewAccount) "Add Account" else "Edit Account") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = !isSaving && !isTesting && account.isValid(),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = account.name,
                onValueChange = onNameChange,
                label = { Text("Account Name *") },
                placeholder = { Text("My S3 Storage") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null)
                },
            )

            HorizontalDivider()

            Text(
                text = "Connection Settings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = account.endpoint,
                onValueChange = onEndpointChange,
                label = { Text("Endpoint URL *") },
                placeholder = { Text("https://s3.amazonaws.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                leadingIcon = {
                    Icon(Icons.Default.Link, contentDescription = null)
                },
            )

            OutlinedTextField(
                value = account.accessKey,
                onValueChange = onAccessKeyChange,
                label = { Text("Access Key *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null)
                },
            )

            OutlinedTextField(
                value = account.secretKey,
                onValueChange = onSecretKeyChange,
                label = { Text("Secret Key *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showSecretKey) VisualTransformation.None else PasswordVisualTransformation(),
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showSecretKey = !showSecretKey }) {
                        Icon(
                            imageVector = if (showSecretKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSecretKey) "Hide" else "Show",
                        )
                    }
                },
            )

            OutlinedTextField(
                value = account.region,
                onValueChange = onRegionChange,
                label = { Text("Region") },
                placeholder = { Text("us-east-1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Public, contentDescription = null)
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Path Style Access",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Required for most S3-compatible services (MinIO, Garage, etc.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = account.usePathStyle,
                    onCheckedChange = onUsePathStyleChange,
                )
            }

            HorizontalDivider()

            // Test Connection Button
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !isTesting && !isSaving && account.isValid(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Connection")
                }
            }

            // Test Result
            if (testResult != null) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (testSuccess) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (testSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint =
                                if (testSuccess) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = testResult,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (testSuccess) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!account.isValid()) {
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
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Please fill in all required fields (*)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
