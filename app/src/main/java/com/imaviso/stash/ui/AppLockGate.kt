package com.imaviso.stash.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imaviso.stash.data.repository.ConfigRepository

/**
 * App-lock gate shared by all entry activities (main + share receiver).
 * When app lock is enabled, biometric auth is required before [content] shows.
 * Lock state intentionally uses plain `remember` so process death re-locks.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configRepository = remember { ConfigRepository.getInstance(context) }
    val appLockEnabled by configRepository.appLockEnabledFlow.collectAsStateWithLifecycle(initialValue = false)

    // unlocked survives rotation but resets on cold start / process death (plain remember)
    var unlocked by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    if (appLockEnabled && !unlocked) {
        val activity = context as? FragmentActivity
        val canAuth =
            activity != null &&
                BiometricManager
                    .from(context)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

        // Auto-prompt on entry when biometrics are available.
        LaunchedEffect(Unit) {
            if (canAuth && activity != null) {
                showBiometricPrompt(activity, onSuccess = { unlocked = true }, onError = { msg -> authError = msg })
            }
        }

        UnlockScreen(
            canAuthenticate = canAuth,
            authError = authError,
            onUnlock = {
                if (canAuth && activity != null) {
                    showBiometricPrompt(activity, onSuccess = { unlocked = true }, onError = { msg -> authError = msg })
                }
            },
        )
    } else {
        content()
    }
}

private fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val callback =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        }
    val prompt = BiometricPrompt(activity, executor, callback)
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
