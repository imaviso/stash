package com.imaviso.stash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.imaviso.stash.data.repository.ConfigRepository
import com.imaviso.stash.ui.navigation.S3NavHost
import com.imaviso.stash.ui.theme.ComposeAppTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : FragmentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            // Permission result handled - notifications will work if granted
            // If not granted, transfers will still work but without notifications
        }

    private lateinit var configRepository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        configRepository = ConfigRepository(this)

        // Request notification permission for Android 13+
        requestNotificationPermission()

        setContent {
            ComposeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val appLockEnabled by
                        remember(configRepository) {
                            configRepository.appLockEnabledFlow
                                .stateIn(lifecycleScope, SharingStarted.Eagerly, false)
                        }.collectAsState()

                    // unlocked survives rotation (rememberSaveable) but resets on cold start.
                    var unlocked by rememberSaveable { mutableStateOf(false) }
                    var authError by remember { mutableStateOf<String?>(null) }

                    if (appLockEnabled && !unlocked) {
                        val context = LocalContext.current
                        val canAuth =
                            BiometricManager
                                .from(context)
                                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

                        // Auto-prompt on entry when biometrics are available.
                        LaunchedEffect(Unit) {
                            if (canAuth) {
                                showBiometricPrompt(onSuccess = { unlocked = true }, onError = { msg -> authError = msg })
                            }
                        }

                        UnlockScreen(
                            canAuthenticate = canAuth,
                            authError = authError,
                            onUnlock = {
                                if (canAuth) {
                                    showBiometricPrompt(onSuccess = { unlocked = true }, onError = { msg -> authError = msg })
                                }
                            },
                        )
                    } else {
                        val navController = rememberNavController()
                        S3NavHost(navController = navController)
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt(
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        val prompt = BiometricPrompt(this, executor, callback)
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock Stash")
                .setSubtitle("Authenticate to access your storage")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            onError(e.message ?: "Unable to start authentication")
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // User previously denied - still request, system will show rationale
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    // First time or "Don't ask again" not checked - request permission
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

@Composable
private fun UnlockScreen(
    canAuthenticate: Boolean,
    authError: String?,
    onUnlock: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Stash is locked",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (canAuthenticate) {
                    "Authenticate to continue"
                } else {
                    "Biometrics aren't set up on this device. Add a fingerprint or face unlock in Android settings, or disable App lock."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (canAuthenticate) {
            Button(onClick = onUnlock) {
                Text("Unlock")
            }
        }
        authError?.let { err ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
