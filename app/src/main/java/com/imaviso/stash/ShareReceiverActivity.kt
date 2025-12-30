package com.imaviso.stash

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.imaviso.stash.ui.screens.ShareUploadScreen
import com.imaviso.stash.ui.theme.ComposeAppTheme

/**
 * Activity that receives share intents from other apps.
 * Allows users to upload shared files to S3.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract URIs from the share intent
        val sharedUris = extractSharedUris(intent)

        setContent {
            ComposeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ShareUploadScreen(
                        sharedUris = sharedUris,
                        onUploadComplete = {
                            // Close this activity after upload
                            finish()
                        },
                        onCancel = {
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun extractSharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                // Single file shared
                val uri =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                uri?.let { uris.add(it) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple files shared
                val uriList =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent
                            .getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
                            ?.filterIsInstance<Uri>()
                    }
                uriList?.let { uris.addAll(it) }
            }
        }

        return uris
    }
}
