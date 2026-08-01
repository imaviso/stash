package com.imaviso.stash.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.imaviso.stash.data.model.FileType
import com.imaviso.stash.data.model.S3Object
import com.imaviso.stash.ui.components.openFileWithExternalApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilePreviewScreen(
    previewableObjects: List<S3Object>,
    currentIndex: Int,
    currentObject: S3Object,
    fileData: ByteArray?,
    streamUrl: String?,
    isLoading: Boolean,
    error: String?,
    onNavigateBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onDownload: (S3Object) -> Unit,
    onDelete: (S3Object) -> Unit,
    onShare: ((S3Object) -> Unit)? = null,
    onRename: ((S3Object) -> Unit)? = null,
    onDetails: ((S3Object) -> Unit)? = null,
    onOpenWith: ((S3Object, File) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Navigation helpers
    val canGoBack = currentIndex > 0
    val canGoForward = currentIndex < previewableObjects.size - 1

    // Pager state - start at the current index
    val pagerState =
        rememberPagerState(
            initialPage = currentIndex,
            pageCount = { previewableObjects.size },
        )

    // Track page changes and notify parent
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != currentIndex) {
            onPageChanged(pagerState.settledPage)
        }
    }

    // Update pager when external index changes (e.g., via delete)
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex && currentIndex < previewableObjects.size) {
            pagerState.scrollToPage(currentIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            currentObject.fileName,
                            maxLines = 1,
                        )
                        if (previewableObjects.size > 1) {
                            Text(
                                "${currentIndex + 1} of ${previewableObjects.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var showOverflow by remember { androidx.compose.runtime.mutableStateOf(false) }
                    // Open with button - shown only when we have file data
                    if (fileData != null) {
                        IconButton(
                            onClick = {
                                try {
                                    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
                                    val tempFile = File(sharedDir, currentObject.fileName)
                                    tempFile.writeBytes(fileData)
                                    if (onOpenWith != null) {
                                        onOpenWith(currentObject, tempFile)
                                    } else {
                                        openFileWithExternalApp(context, tempFile, currentObject.mimeType)?.let { message ->
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to open: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open with")
                        }
                    }
                    IconButton(onClick = { onDownload(currentObject) }) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                    // Overflow: share link, rename, details, delete
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            if (onShare != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Share link") },
                                    onClick = {
                                        showOverflow = false
                                        onShare(currentObject)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                )
                            }
                            if (onRename != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        showOverflow = false
                                        onRename(currentObject)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                )
                            }
                            if (onDetails != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Details") },
                                    onClick = {
                                        showOverflow = false
                                        onDetails(currentObject)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                )
                            }
                            androidx.compose.material3.HorizontalDivider()
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showOverflow = false
                                    onDelete(currentObject)
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
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { previewableObjects[it].key },
            ) { page ->
                val pageObject = previewableObjects[page]
                val isCurrentPage = page == pagerState.settledPage

                // Only show actual content for current page, neighbors show placeholder
                if (isCurrentPage && pageObject.key == currentObject.key) {
                    // Current page with loaded data
                    PreviewContent(
                        s3Object = currentObject,
                        fileData = fileData,
                        streamUrl = streamUrl,
                        isLoading = isLoading,
                        error = error,
                    )
                } else {
                    // Placeholder for other pages (will load when swiped to)
                    PreviewPlaceholder(s3Object = pageObject)
                }
            }

            // Navigation tap zones (only if more than one file)
            if (previewableObjects.size > 1) {
                // State to track arrow visibility
                var showLeftArrow by remember { mutableStateOf(false) }
                var showRightArrow by remember { mutableStateOf(false) }

                // Auto-hide arrows after delay
                LaunchedEffect(showLeftArrow) {
                    if (showLeftArrow) {
                        kotlinx.coroutines.delay(800)
                        showLeftArrow = false
                    }
                }
                LaunchedEffect(showRightArrow) {
                    if (showRightArrow) {
                        kotlinx.coroutines.delay(800)
                        showRightArrow = false
                    }
                }

                // Previous tap zone (left edge)
                if (canGoBack) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight(0.6f)
                                .width(60.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            showLeftArrow = true
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(currentIndex - 1)
                                            }
                                        },
                                    )
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showLeftArrow,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous",
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // Next tap zone (right edge)
                if (canGoForward) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(0.6f)
                                .width(60.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            showRightArrow = true
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(currentIndex + 1)
                                            }
                                        },
                                    )
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showRightArrow,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next",
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder(s3Object: S3Object) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading ${s3Object.fileName}...")
            Text(
                s3Object.formattedSize,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewContent(
    s3Object: S3Object,
    fileData: ByteArray?,
    streamUrl: String?,
    isLoading: Boolean,
    error: String?,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading ${s3Object.fileName}...")
                    Text(
                        s3Object.formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Failed to load file")
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            streamUrl != null -> {
                // Streaming from presigned URL
                when (s3Object.fileType) {
                    FileType.VIDEO -> VideoStreamPreview(streamUrl)
                    FileType.AUDIO -> AudioStreamPreview(streamUrl, s3Object.fileName)
                    FileType.IMAGE -> ZoomableImagePreview(streamUrl)
                    else -> UnsupportedPreview(s3Object)
                }
            }

            fileData != null -> {
                when (s3Object.fileType) {
                    FileType.IMAGE -> {
                        ZoomableImagePreview(fileData)
                    }

                    FileType.VIDEO, FileType.AUDIO -> {
                        // Should use streamUrl, but fallback to data if available
                        Text("Use streaming URL for video/audio")
                    }

                    FileType.TEXT -> {
                        TextPreview(fileData)
                    }

                    FileType.PDF -> {
                        PdfPreview(fileData, s3Object.fileName)
                    }

                    FileType.OTHER -> {
                        UnsupportedPreview(s3Object)
                    }
                }
            }

            else -> {
                Text("No data available")
            }
        }
    }
}

// Legacy single-file preview for backward compatibility
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    s3Object: S3Object,
    fileData: ByteArray?,
    streamUrl: String?, // For streaming video/audio
    isLoading: Boolean,
    error: String?,
    onNavigateBack: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onOpenWith: ((File) -> Unit)? = null, // Callback when file is ready for external app
) {
    FilePreviewScreen(
        previewableObjects = listOf(s3Object),
        currentIndex = 0,
        currentObject = s3Object,
        fileData = fileData,
        streamUrl = streamUrl,
        isLoading = isLoading,
        error = error,
        onNavigateBack = onNavigateBack,
        onPageChanged = { },
        onDownload = { onDownload() },
        onDelete = { onDelete() },
    )
}

/**
 * Zoomable image preview shared by byte-array and streamed (presigned URL)
 * variants - [model] is passed straight to Coil (ByteArray or URL String).
 */
@Composable
private fun ZoomableImagePreview(model: Any?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset offset when returning to normal scale
    LaunchedEffect(scale) {
        if (scale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        // Only allow panning when zoomed in
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Toggle between 1x and 2.5x zoom on double tap
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }

        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalContext.current)
                    .data(model)
                    .crossfade(true)
                    .build(),
            contentDescription = "Image preview",
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            contentScale = ContentScale.Fit,
            onState = { imageState = it },
        )

        when (imageState) {
            is AsyncImagePainter.State.Loading -> {
                CircularProgressIndicator(color = Color.White)
            }

            is AsyncImagePainter.State.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Failed to load image",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            else -> { }
        }
    }
}

/**
 * Shared ExoPlayer setup for streaming previews from presigned URLs.
 * Released automatically when the caller leaves composition.
 */
@Composable
private fun rememberStreamExoPlayer(
    streamUrl: String,
    playWhenReady: Boolean,
    onError: (String) -> Unit,
): ExoPlayer {
    val context = LocalContext.current

    val exoPlayer =
        remember(streamUrl) {
            ExoPlayer.Builder(context).build().apply {
                val mediaItem = MediaItem.fromUri(streamUrl)
                setMediaItem(mediaItem)
                addListener(
                    object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.e("StreamPreview", "ExoPlayer error: ${error.message}", error)
                            onError("Playback error: ${error.message}")
                        }
                    },
                )
                prepare()
                this.playWhenReady = playWhenReady
            }
        }

    DisposableEffect(streamUrl) {
        onDispose {
            exoPlayer.release()
        }
    }

    return exoPlayer
}

@Composable
private fun PlaybackErrorCard(error: String) {
    Card(
        modifier = Modifier.padding(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Text(
            text = error,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

// Streaming video preview from presigned URL - no memory loading required
@Composable
private fun VideoStreamPreview(streamUrl: String) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val exoPlayer = rememberStreamExoPlayer(streamUrl, playWhenReady = true) { errorMessage = it }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Show error if playback fails
        errorMessage?.let { error ->
            PlaybackErrorCard(error)
        }
    }
}

// Streaming audio preview from presigned URL
@Composable
private fun AudioStreamPreview(
    streamUrl: String,
    fileName: String,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val exoPlayer = rememberStreamExoPlayer(streamUrl, playWhenReady = false) { errorMessage = it }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Show error if playback fails
        errorMessage?.let { error ->
            PlaybackErrorCard(error)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(128.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            fileName,
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(modifier = Modifier.height(32.dp))

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp),
        )
    }
}

@Composable
private fun TextPreview(data: ByteArray) {
    val text =
        remember(data) {
            try {
                String(data, Charsets.UTF_8)
            } catch (e: Exception) {
                "Unable to decode text content"
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = text,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PdfPreview(
    fileData: ByteArray,
    fileName: String,
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var maxPages by remember { mutableIntStateOf(0) }

    // Renderer kept open for the lifetime of this preview; pages render lazily
    // per visible item instead of all up front (avoids OOM on large PDFs).
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var parcelFileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }
    // PdfRenderer is not thread-safe - serialize page rendering.
    val renderMutex = remember { kotlinx.coroutines.sync.Mutex() }

    // Open the PDF on composition (limit preview to first 50 pages)
    LaunchedEffect(fileData) {
        isLoading = true
        error = null

        withContext(Dispatchers.IO) {
            try {
                // Write PDF data to temp file
                val file = File.createTempFile("pdf_preview", ".pdf", context.cacheDir)
                file.writeBytes(fileData)

                // Open PDF with PdfRenderer
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)

                pageCount = renderer.pageCount
                maxPages = minOf(renderer.pageCount, 50)
                tempFile = file
                parcelFileDescriptor = pfd
                pdfRenderer = renderer
                isLoading = false
            } catch (e: Exception) {
                android.util.Log.e("PdfPreview", "Failed to open PDF", e)
                error = "Failed to render PDF: ${e.message}"
                isLoading = false
            }
        }
    }

    // Clean up renderer when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
            tempFile?.delete()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Rendering PDF...")
                }
            }

            error != null -> {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        fileName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            maxPages > 0 -> {
                val listState = rememberLazyListState()

                Column(modifier = Modifier.fillMaxSize()) {
                    // Page indicator
                    val currentPage =
                        remember(listState.firstVisibleItemIndex) {
                            listState.firstVisibleItemIndex + 1
                        }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text =
                                    if (pageCount > maxPages) {
                                        "Page $currentPage of $maxPages ($pageCount total)"
                                    } else {
                                        "Page $currentPage of $pageCount"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // PDF pages - rendered lazily per visible item
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(maxPages) { index ->
                            PdfPageItem(
                                renderer = pdfRenderer,
                                renderMutex = renderMutex,
                                index = index,
                            )
                        }

                        // Show indicator if there are more pages
                        if (pageCount > maxPages) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "${pageCount - maxPages} more pages",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Download the file to view all pages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                // Fallback - no pages rendered
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        fileName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No pages to display",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A single lazily-rendered PDF page. The bitmap is produced only while the item
 * is composed (visible) and drops out of memory when the item leaves composition.
 */
@Composable
private fun PdfPageItem(
    renderer: PdfRenderer?,
    renderMutex: kotlinx.coroutines.sync.Mutex,
    index: Int,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, renderer, index) {
        if (renderer == null) return@produceState
        value =
            withContext(Dispatchers.IO) {
                renderMutex.withLock {
                    try {
                        val page = renderer.openPage(index)
                        // Render at native page size (screen width), not 2x - keeps memory low
                        val pageBitmap =
                            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        // Fill with white background
                        pageBitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        pageBitmap
                    } catch (e: Exception) {
                        android.util.Log.e("PdfPreview", "Failed to render page $index", e)
                        null
                    }
                }
            }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        val pageBitmap = bitmap
        if (pageBitmap != null) {
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun UnsupportedPreview(s3Object: S3Object) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            s3Object.fileName,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Preview not available for this file type.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            s3Object.formattedSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


