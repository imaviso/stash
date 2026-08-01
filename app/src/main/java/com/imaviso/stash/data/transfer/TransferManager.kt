package com.imaviso.stash.data.transfer

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.imaviso.stash.data.ObjectOperations
import com.imaviso.stash.data.model.ObjectKey
import com.imaviso.stash.data.model.SharedFileInfo
import com.imaviso.stash.worker.DownloadWorker
import com.imaviso.stash.worker.UploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Direction of a transfer.
 */
enum class TransferType {
    UPLOAD,
    DOWNLOAD,
}

/**
 * Lifecycle state of a transfer.
 * ACTIVE: in progress. Terminal states are FINAL: once a record reaches
 * COMPLETED/FAILED/CANCELLED no later transition or progress update can move
 * it out. Terminal records remain for history until cleared.
 */
enum class TransferState {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this != ACTIVE
}

/**
 * A single transfer (upload or download) tracked across the app.
 */
data class TransferInfo(
    val id: String,
    val fileName: String,
    val type: TransferType,
    val progress: Int = 0,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val status: String = "",
    val error: String? = null,
    val state: TransferState = TransferState.ACTIVE,
    val timestamp: Long = System.currentTimeMillis(),
    val bucketName: String = "",
)

/**
 * Record store: records keyed by transfer id so later progress emissions for
 * the same transfer replace in place. Owns the lifecycle invariant:
 * terminal states are final.
 */
internal class TransferRecordStore {
    private val _records = MutableStateFlow<Map<String, TransferInfo>>(emptyMap())
    val records: StateFlow<Map<String, TransferInfo>> = _records.asStateFlow()

    /**
     * Insert or replace the record for the transfer's id.
     * INVARIANT: a record already in a terminal state is never replaced —
     * late progress emissions for finished transfers are dropped.
     */
    fun upsert(record: TransferInfo) {
        _records.update { map ->
            val existing = map[record.id]
            if (existing != null && existing.state.isTerminal) {
                map
            } else {
                map + (record.id to record)
            }
        }
    }

    /**
     * Mark an existing active transfer as terminal. If the id is unknown, the
     * record is inserted so the terminal event is not lost.
     * INVARIANT: terminal states are final — if the existing record is
     * already terminal, this is a no-op (fixes the folder-download cancel
     * overwrite: CANCELLED then late COMPLETED stays CANCELLED).
     */
    fun markTerminal(
        id: String,
        state: TransferState,
        fileName: String,
        type: TransferType,
        bucketName: String,
        error: String? = null,
        totalBytes: Long = 0,
        bytesTransferred: Long = 0,
    ) {
        val now = System.currentTimeMillis()
        _records.update { map ->
            val existing = map[id]
            if (existing != null && existing.state.isTerminal) return@update map
            val terminal =
                if (existing != null) {
                    existing.copy(
                        state = state,
                        error = error,
                        timestamp = now,
                        progress = if (state == TransferState.COMPLETED) 100 else existing.progress,
                        bytesTransferred = if (bytesTransferred > 0) bytesTransferred else existing.bytesTransferred,
                        totalBytes = if (totalBytes > 0) totalBytes else existing.totalBytes,
                        status = state.name.lowercase().replaceFirstChar { it.uppercase() },
                    )
                } else {
                    TransferInfo(
                        id = id,
                        fileName = fileName,
                        type = type,
                        state = state,
                        error = error,
                        timestamp = now,
                        totalBytes = totalBytes,
                        bytesTransferred = bytesTransferred,
                        progress = if (state == TransferState.COMPLETED) 100 else 0,
                        status = state.name.lowercase().replaceFirstChar { it.uppercase() },
                        bucketName = bucketName,
                    )
                }
            map + (id to terminal)
        }
    }

    /**
     * Remove all terminal records, keeping only currently active transfers.
     */
    fun clearHistory() {
        _records.update { map -> map.filterValues { it.state == TransferState.ACTIVE } }
    }
}

/**
 * Lazy value with test reset. Production calls [value] once and caches;
 * unit tests construct their own instance per case.
 */
private class ResettableLazy<T>(
    private val initializer: () -> T,
) {
    @Volatile
    private var cached: T? = null

    fun value(): T =
        cached ?: synchronized(this) {
            cached ?: initializer().also { cached = it }
        }
}

/**
 * Single authority for transfers: records + lifecycle invariants + execution
 * dispatch. Callers see only this module; WorkManager (background) and the
 * in-process executor (foreground/folder/share) are adapters behind
 * [TransferExecutor].
 *
 * Reconciliation on init: works still RUNNING/ENQUEUED in WorkManager are
 * seeded back into [records] so background transfers survive process death.
 * In-process transfers die with the process (foreground UX only).
 */
class TransferManager internal constructor(
    context: Context?,
    executorFactory: (CoroutineScope) -> TransferExecutor,
) {
    companion object {
        /** Files larger than this route to the WorkManager (background) adapter. */
        const val BACKGROUND_THRESHOLD_BYTES = 5L * 1024 * 1024

        internal const val UPLOAD_TAG = "upload"
        internal const val DOWNLOAD_TAG = "download"
        internal const val TRANSFER_TAG_PREFIX = "transfer_"
        private const val UNKNOWN_FILE_NAME = "Unknown file"

        private val UploadKeys =
            WorkerKeys(
                progress = UploadWorker.KEY_PROGRESS,
                status = UploadWorker.KEY_STATUS,
                bytes = UploadWorker.KEY_BYTES_UPLOADED,
                total = UploadWorker.KEY_TOTAL_BYTES,
                error = UploadWorker.KEY_ERROR,
                fileName = UploadWorker.KEY_FILE_NAME,
                bucket = UploadWorker.KEY_BUCKET_NAME,
                statusPreparing = UploadWorker.STATUS_PREPARING,
                activeLabel = "Uploading...",
                defaultError = "Upload failed",
            )

        private val DownloadKeys =
            WorkerKeys(
                progress = DownloadWorker.KEY_PROGRESS,
                status = DownloadWorker.KEY_STATUS,
                bytes = DownloadWorker.KEY_BYTES_DOWNLOADED,
                total = DownloadWorker.KEY_TOTAL_BYTES,
                error = DownloadWorker.KEY_ERROR,
                fileName = DownloadWorker.KEY_FILE_NAME,
                bucket = DownloadWorker.KEY_BUCKET_NAME,
                statusPreparing = DownloadWorker.STATUS_PREPARING,
                activeLabel = "Downloading...",
                defaultError = "Download failed",
            )

        @Volatile
        private var instance: TransferManager? = null

        /**
         * Process-wide singleton, same pattern as ConfigRepository/S3Service.
         */
        fun getInstance(context: Context): TransferManager =
            instance ?: synchronized(this) {
                val appContext = context.applicationContext
                instance ?: TransferManager(
                    appContext,
                    executorFactory = { scope ->
                        CompositeTransferExecutor(
                            background = WorkManagerTransferExecutor(appContext),
                            inProcess = InProcessTransferExecutor(scope),
                        )
                    },
                ).also { instance = it }
            }
    }

    private val applicationContext: Context? = context?.applicationContext

    /** Record store; internal so unit tests can drive records directly. */
    internal val store = TransferRecordStore()

    /** All transfer records, keyed by transfer id. */
    val records: StateFlow<Map<String, TransferInfo>> = store.records

    /** Currently running transfers, in insertion order. */
    val activeTransfers: Flow<List<TransferInfo>> =
        records.map { map -> map.values.filter { it.state == TransferState.ACTIVE } }

    /** Terminal records, most recent first. */
    val historyTransfers: Flow<List<TransferInfo>> =
        records
            .map { map ->
                map.values
                    .filter { it.state.isTerminal }
                    .sortedByDescending { it.timestamp }
            }

    /** Count of active transfers (badge). */
    val activeTransferCount: Flow<Int> =
        records.map { map -> map.values.count { it.state == TransferState.ACTIVE } }

    /** Application-scoped driver for collectors + the in-process adapter. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val executor: TransferExecutor = executorFactory(scope)

    /**
     * Android environment for the in-process adapter. Lazy so unit tests
     * (context == null) can construct TransferManager on the plain JVM.
     */
    private val environment = ResettableLazy { applicationContext?.let { S3Environment(it) } }

    /** fileName/bucket hints for terminal marks observed from WorkManager. */
    private data class WorkHint(
        val fileName: String,
        val bucketName: String,
    )

    private val workHints = ConcurrentHashMap<String, WorkHint>()

    /** Data-key names shared by the two worker adapters (contract preserved). */
    private class WorkerKeys(
        val progress: String,
        val status: String,
        val bytes: String,
        val total: String,
        val error: String,
        val fileName: String,
        val bucket: String,
        val statusPreparing: String,
        val activeLabel: String,
        val defaultError: String,
    )

    init {
        applicationContext?.let { ctx ->
            val workManager = WorkManager.getInstance(ctx)
            reconcileBackgroundWorks(workManager)
            observeWorks(workManager, UPLOAD_TAG, TransferType.UPLOAD, UploadKeys)
            observeWorks(workManager, DOWNLOAD_TAG, TransferType.DOWNLOAD, DownloadKeys)
        }
    }

    // ==================== ROUTING ====================

    /**
     * Background-vs-foreground routing rule (>5MB goes to WorkManager).
     * Single owner — screens and ViewModels must not re-implement it.
     */
    fun shouldRunInBackground(sizeBytes: Long): Boolean = sizeBytes > BACKGROUND_THRESHOLD_BYTES

    // ==================== DISPATCH ====================

    /**
     * Enqueue an upload. Defaults to background when [size] exceeds
     * [BACKGROUND_THRESHOLD_BYTES]; foreground uploads run on the in-process
     * adapter and are tracked (record + cancellation) the same way.
     * [fileUri] is the string form of the source content Uri (kept as String
     * so the module body stays JVM-testable). Returns the transfer id.
     */
    fun enqueueUpload(
        fileUri: String,
        bucket: String,
        prefix: String,
        fileName: String,
        size: Long,
        contentType: String,
        background: Boolean? = null,
    ): String {
        val transferId = newTransferId()
        val objectKey = ObjectKey(prefix).child(fileName).key
        val runInBackground = background ?: shouldRunInBackground(size)

        workHints[transferId] = WorkHint(fileName, bucket)
        store.upsert(
            TransferInfo(
                id = transferId,
                fileName = fileName,
                type = TransferType.UPLOAD,
                totalBytes = size,
                status = if (runInBackground) "Preparing..." else "Uploading...",
                bucketName = bucket,
            ),
        )

        if (runInBackground) {
            executor.enqueueUpload(
                transferId = transferId,
                bucketName = bucket,
                objectKey = objectKey,
                fileUri = fileUri,
                contentType = contentType,
                fileName = fileName,
            )
        } else {
            executor.launch(transferId) {
                val env = environment.value() ?: return@launch
                runForegroundUpload(env, transferId, fileUri, bucket, objectKey, fileName, size, contentType)
            }
        }
        return transferId
    }

    /**
     * Enqueue a background download through WorkManager. Returns the transfer id.
     * (Small foreground downloads keep their ViewModel-scoped progress overlay
     * and do not go through the transfer store.)
     */
    fun enqueueDownload(
        bucket: String,
        key: String,
        fileName: String,
        size: Long,
        mimeType: String,
    ): String {
        val transferId = newTransferId()

        workHints[transferId] = WorkHint(fileName, bucket)
        store.upsert(
            TransferInfo(
                id = transferId,
                fileName = fileName,
                type = TransferType.DOWNLOAD,
                totalBytes = size,
                status = "Preparing...",
                bucketName = bucket,
            ),
        )

        executor.enqueueDownload(
            transferId = transferId,
            bucketName = bucket,
            objectKey = key,
            fileName = fileName,
            fileSize = size,
            mimeType = mimeType,
        )
        return transferId
    }

    /**
     * Enqueue a recursive folder download on the in-process adapter.
     * The job sits in the executor registry, so [cancel] actually cancels it;
     * the terminal-final invariant makes the post-cancel overwrite impossible.
     * Returns the transfer id.
     */
    fun enqueueFolderDownload(
        bucket: String,
        folderKey: String,
        folderName: String,
    ): String {
        val transferId = newTransferId()

        workHints[transferId] = WorkHint(folderName, bucket)
        store.upsert(
            TransferInfo(
                id = transferId,
                fileName = folderName,
                type = TransferType.DOWNLOAD,
                status = "Scanning...",
                bucketName = bucket,
            ),
        )

        executor.launch(transferId) {
            val env = environment.value() ?: return@launch
            runFolderDownload(env, transferId, bucket, folderKey, folderName)
        }
        return transferId
    }

    /**
     * Enqueue a share-sheet upload batch on the in-process adapter. Gains
     * record tracking + cancellation. Returns the transfer id.
     */
    fun enqueueShareUpload(
        files: List<SharedFileInfo>,
        bucket: String,
        prefix: String,
    ): String {
        val transferId = newTransferId()
        val displayName = if (files.size == 1) files.single().fileName else "${files.size} files"
        val totalBytes = files.sumOf { it.size }

        workHints[transferId] = WorkHint(displayName, bucket)
        store.upsert(
            TransferInfo(
                id = transferId,
                fileName = displayName,
                type = TransferType.UPLOAD,
                totalBytes = totalBytes,
                status = "Preparing upload...",
                bucketName = bucket,
            ),
        )

        executor.launch(transferId) {
            val env = environment.value() ?: return@launch
            runShareUpload(env, transferId, files, bucket, prefix, totalBytes)
        }
        return transferId
    }

    /**
     * Cancel any transfer, background or in-process — callers no longer
     * distinguish. The record is marked CANCELLED immediately; a late
     * terminal event from the executor cannot overwrite it (invariant).
     */
    fun cancel(id: String) {
        val record = store.records.value[id]
        if (record != null && record.state == TransferState.ACTIVE) {
            store.markTerminal(
                id = id,
                state = TransferState.CANCELLED,
                fileName = record.fileName,
                type = record.type,
                bucketName = record.bucketName,
            )
        }
        executor.cancel(id)
        workHints.remove(id)
    }

    /**
     * Remove all terminal records, keeping only currently active transfers.
     */
    fun clearHistory() {
        store.clearHistory()
    }

    // ==================== IN-PROCESS EXECUTION ====================

    private suspend fun runForegroundUpload(
        env: S3Environment,
        transferId: String,
        fileUri: String,
        bucket: String,
        objectKey: String,
        fileName: String,
        size: Long,
        contentType: String,
    ) {
        try {
            val input =
                env.openInputStream(fileUri)
                    ?: throw java.io.IOException("Cannot read file")
            input.use { stream ->
                env.s3
                    .uploadObjectFromStream(
                        bucketName = bucket,
                        key = objectKey,
                        inputStream = stream,
                        contentLength = size,
                        contentType = contentType,
                        cacheDir = env.cacheDir,
                        onProgress = { bytesSent, totalBytes, phase ->
                            if (phase == "preparing") {
                                val progress = if (totalBytes > 0) ((bytesSent * 100) / totalBytes).toInt() else 0
                                store.upsert(
                                    TransferInfo(
                                        id = transferId,
                                        fileName = fileName,
                                        type = TransferType.UPLOAD,
                                        progress = progress,
                                        bytesTransferred = bytesSent,
                                        totalBytes = totalBytes,
                                        status = "Uploading...",
                                        bucketName = bucket,
                                    ),
                                )
                            }
                        },
                    ).getOrThrow()
            }
            store.markTerminal(
                id = transferId,
                state = TransferState.COMPLETED,
                fileName = fileName,
                type = TransferType.UPLOAD,
                bucketName = bucket,
                totalBytes = size,
                bytesTransferred = size,
            )
        } catch (e: CancellationException) {
            markCancelled(transferId, fileName, TransferType.UPLOAD, bucket)
            throw e
        } catch (e: Throwable) {
            store.markTerminal(
                id = transferId,
                state = TransferState.FAILED,
                fileName = fileName,
                type = TransferType.UPLOAD,
                bucketName = bucket,
                error = e.message ?: "Upload failed",
            )
        }
    }

    private suspend fun runFolderDownload(
        env: S3Environment,
        transferId: String,
        bucket: String,
        folderKey: String,
        folderName: String,
    ) {
        try {
            ObjectOperations(env.s3)
                .downloadFolder(
                    context = env.appContext,
                    bucketName = bucket,
                    folderKey = folderKey,
                    folderName = folderName,
                    onFileProgress = { filesDone, totalFiles, bytesDone, bytesTotal ->
                        store.upsert(
                            TransferInfo(
                                id = transferId,
                                fileName = folderName,
                                type = TransferType.DOWNLOAD,
                                progress = if (bytesTotal > 0) ((bytesDone * 100) / bytesTotal).toInt() else 0,
                                bytesTransferred = bytesDone,
                                totalBytes = bytesTotal,
                                status = "Downloading ${filesDone + 1}/$totalFiles",
                                bucketName = bucket,
                            ),
                        )
                    },
                ).onSuccess { outcome ->
                    when {
                        outcome.totalFiles == 0 ->
                            store.markTerminal(
                                id = transferId,
                                state = TransferState.CANCELLED,
                                fileName = folderName,
                                type = TransferType.DOWNLOAD,
                                bucketName = bucket,
                                error = "Folder is empty",
                            )

                        outcome.downloadedFiles == outcome.totalFiles ->
                            store.markTerminal(
                                id = transferId,
                                state = TransferState.COMPLETED,
                                fileName = folderName,
                                type = TransferType.DOWNLOAD,
                                bucketName = bucket,
                                totalBytes = outcome.totalBytes,
                                bytesTransferred = outcome.downloadedBytes,
                            )

                        else ->
                            store.markTerminal(
                                id = transferId,
                                state = TransferState.FAILED,
                                fileName = folderName,
                                type = TransferType.DOWNLOAD,
                                bucketName = bucket,
                                error = "Only ${outcome.downloadedFiles} of ${outcome.totalFiles} files downloaded",
                                totalBytes = outcome.totalBytes,
                                bytesTransferred = outcome.downloadedBytes,
                            )
                    }
                }.onFailure { e ->
                    // S3 adapters wrap calls in runCatching, which can capture
                    // CancellationException into Result.failure — detect it so
                    // cancellation never lands as a FAILED record.
                    if (e is CancellationException) {
                        markCancelled(transferId, folderName, TransferType.DOWNLOAD, bucket)
                    } else {
                        store.markTerminal(
                            id = transferId,
                            state = TransferState.FAILED,
                            fileName = folderName,
                            type = TransferType.DOWNLOAD,
                            bucketName = bucket,
                            error = e.message ?: "Failed to download folder",
                        )
                    }
                }
        } catch (e: CancellationException) {
            markCancelled(transferId, folderName, TransferType.DOWNLOAD, bucket)
            throw e
        }
    }

    private suspend fun runShareUpload(
        env: S3Environment,
        transferId: String,
        files: List<SharedFileInfo>,
        bucket: String,
        prefix: String,
        totalBytes: Long,
    ) {
        try {
            var successCount = 0
            var bytesDone = 0L
            val displayName = workHints[transferId]?.fileName ?: "files"

            files.forEachIndexed { index, file ->
                store.upsert(
                    TransferInfo(
                        id = transferId,
                        fileName = displayName,
                        type = TransferType.UPLOAD,
                        progress = if (totalBytes > 0) ((bytesDone * 100) / totalBytes).toInt() else 0,
                        bytesTransferred = bytesDone,
                        totalBytes = totalBytes,
                        status = "Uploading ${index + 1}/${files.size}: ${file.fileName}",
                        bucketName = bucket,
                    ),
                )

                val objectKey = ObjectKey(prefix).child(file.fileName).key
                env.openInputStream(file.uri.toString())?.use { stream ->
                    val result =
                        env.s3.uploadObjectFromStream(
                            bucketName = bucket,
                            key = objectKey,
                            inputStream = stream,
                            contentLength = file.size,
                            contentType = file.mimeType,
                            cacheDir = env.cacheDir,
                        )
                    if (result.isSuccess) {
                        successCount++
                        bytesDone += file.size
                    }
                }
            }

            when {
                successCount == files.size ->
                    store.markTerminal(
                        id = transferId,
                        state = TransferState.COMPLETED,
                        fileName = displayName,
                        type = TransferType.UPLOAD,
                        bucketName = bucket,
                        totalBytes = totalBytes,
                        bytesTransferred = bytesDone,
                    )

                successCount > 0 ->
                    store.markTerminal(
                        id = transferId,
                        state = TransferState.COMPLETED,
                        fileName = displayName,
                        type = TransferType.UPLOAD,
                        bucketName = bucket,
                        error = "Uploaded $successCount/${files.size} files. Some uploads failed.",
                        totalBytes = totalBytes,
                        bytesTransferred = bytesDone,
                    )

                else ->
                    store.markTerminal(
                        id = transferId,
                        state = TransferState.FAILED,
                        fileName = displayName,
                        type = TransferType.UPLOAD,
                        bucketName = bucket,
                        error = "All uploads failed. Please check your connection and try again.",
                    )
            }
        } catch (e: CancellationException) {
            markCancelled(transferId, workHints[transferId]?.fileName ?: "files", TransferType.UPLOAD, bucket)
            throw e
        }
    }

    private fun markCancelled(
        id: String,
        fileName: String,
        type: TransferType,
        bucket: String,
        error: String? = null,
    ) {
        store.markTerminal(
            id = id,
            state = TransferState.CANCELLED,
            fileName = fileName,
            type = type,
            bucketName = bucket,
            error = error,
        )
    }

    // ==================== WORKMANAGER OBSERVATION ====================

    private fun observeWorks(
        workManager: WorkManager,
        tag: String,
        type: TransferType,
        keys: WorkerKeys,
    ) {
        scope.launch {
            workManager.getWorkInfosByTagFlow(tag).collect { infos ->
                infos.forEach { info -> applyWorkInfo(type, keys, info) }
            }
        }
    }

    /** Map one WorkInfo into the record store (progress + terminal states). */
    private fun applyWorkInfo(
        type: TransferType,
        keys: WorkerKeys,
        info: WorkInfo,
    ) {
        val transferId = transferIdOf(info) ?: return
        val progress = info.progress

        progress.getString(keys.fileName)?.let { name ->
            workHints[transferId] = WorkHint(name, progress.getString(keys.bucket) ?: "")
        }

        when (info.state) {
            WorkInfo.State.RUNNING -> {
                val hint = workHints[transferId]
                store.upsert(
                    TransferInfo(
                        id = transferId,
                        fileName = hint?.fileName ?: UNKNOWN_FILE_NAME,
                        type = type,
                        progress = progress.getInt(keys.progress, 0),
                        bytesTransferred = progress.getLong(keys.bytes, 0L),
                        totalBytes = progress.getLong(keys.total, 0L),
                        status =
                            if (progress.getString(keys.status) == keys.statusPreparing) {
                                "Preparing..."
                            } else {
                                keys.activeLabel
                            },
                        bucketName = hint?.bucketName ?: "",
                    ),
                )
            }

            WorkInfo.State.SUCCEEDED -> {
                val hint = workHints.remove(transferId)
                store.markTerminal(
                    id = transferId,
                    state = TransferState.COMPLETED,
                    fileName = hint?.fileName ?: UNKNOWN_FILE_NAME,
                    type = type,
                    bucketName = hint?.bucketName ?: "",
                    totalBytes = info.outputData.getLong(keys.total, 0L),
                    bytesTransferred = info.outputData.getLong(keys.bytes, 0L),
                )
            }

            WorkInfo.State.FAILED -> {
                val hint = workHints.remove(transferId)
                store.markTerminal(
                    id = transferId,
                    state = TransferState.FAILED,
                    fileName = hint?.fileName ?: UNKNOWN_FILE_NAME,
                    type = type,
                    bucketName = hint?.bucketName ?: "",
                    error = info.outputData.getString(keys.error) ?: keys.defaultError,
                )
            }

            WorkInfo.State.CANCELLED -> {
                val hint = workHints.remove(transferId)
                markCancelled(
                    id = transferId,
                    fileName = hint?.fileName ?: UNKNOWN_FILE_NAME,
                    type = type,
                    bucket = hint?.bucketName ?: "",
                )
            }

            else -> { /* ENQUEUED, BLOCKED - record already seeded at enqueue/reconcile */ }
        }
    }

    /**
     * Seed records for works still RUNNING/ENQUEUED after process death.
     * The live collectors keep them updated from here on.
     */
    private fun reconcileBackgroundWorks(workManager: WorkManager) {
        scope.launch {
            runCatching {
                listOf(UPLOAD_TAG to TransferType.UPLOAD, DOWNLOAD_TAG to TransferType.DOWNLOAD)
                    .forEach { (tag, type) ->
                        workManager
                            .getWorkInfosByTag(tag)
                            .get()
                            .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                            .forEach { info ->
                                val transferId = transferIdOf(info) ?: return@forEach
                                if (!store.records.value.containsKey(transferId)) {
                                    store.upsert(
                                        TransferInfo(
                                            id = transferId,
                                            fileName = UNKNOWN_FILE_NAME,
                                            type = type,
                                            status = "Preparing...",
                                        ),
                                    )
                                }
                            }
                    }
            }
        }
    }

    private fun transferIdOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(TRANSFER_TAG_PREFIX) }?.removePrefix(TRANSFER_TAG_PREFIX)

    private fun newTransferId(): String = java.util.UUID.randomUUID().toString()
}
