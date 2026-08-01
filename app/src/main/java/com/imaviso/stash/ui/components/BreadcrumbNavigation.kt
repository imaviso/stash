package com.imaviso.stash.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared breadcrumb row: root (bucket) + path segments.
 * [onNavigateToSegment] is called with the tapped history index (0 = root).
 */
@Composable
fun BreadcrumbNavigation(
    pathHistory: List<String>,
    rootLabel: String,
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
            Text(rootLabel, style = MaterialTheme.typography.bodyMedium)
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
