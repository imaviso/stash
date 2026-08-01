package com.imaviso.stash.data.transfer

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.imaviso.stash.data.remote.S3Operations
import com.imaviso.stash.data.remote.S3Service
import com.imaviso.stash.worker.DownloadWorker
import com.imaviso.stash.worker.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-only capabilities the in-process transfer bodies need. Lazily held
 * by TransferManager so the record store + routing rule stay JVM-testable.
 */
internal class S3Environment(
    val appContext: Context,
) {
    val s3: S3Operations
        get() = S3Service.getInstance()

    val cacheDir: File
        get() = appContext.cacheDir

    fun openInputStream(fileUri: String): InputStream? = appContext.contentResolver.openInputStream(Uri.parse(fileUri))
}

/**
 * Execution seam for the transfer module. Two production adapters:
 * [WorkManagerTransferExecutor] (background, survives process death) and
 * [InProcessTransferExecutor] (foreground/folder/share). A third fake
 * adapter ([FakeTransferExecutor]) serves unit tests.
 */
internal interface TransferExecutor {
    fun enqueueUpload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileUri: String,
        contentType: String,
        fileName: String,
    )

    fun enqueueDownload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    )

    /** Run [block] on the in-process adapter with its Job registered. */
    fun launch(
        transferId: String,
        block: suspend () -> Unit,
    ): Job

    /**
     * Cancel by transfer id — WorkManager cancel by tag for background work,
     * Job cancellation for in-process work. Must tolerate unknown ids.
     */
    fun cancel(transferId: String)
}

/**
 * Background adapter: posts work to WorkManager. Cancellation is by the
 * per-transfer tag; TransferManager's WorkInfo collectors observe the result.
 */
internal class WorkManagerTransferExecutor(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : TransferExecutor {
    override fun enqueueUpload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileUri: String,
        contentType: String,
        fileName: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    UploadWorker.createInputData(
                        bucketName = bucketName,
                        objectKey = objectKey,
                        fileUri = fileUri,
                        contentType = contentType,
                        fileName = fileName,
                        transferId = transferId,
                    ),
                ).addTag(TransferManager.UPLOAD_TAG)
                .addTag(TransferManager.TRANSFER_TAG_PREFIX + transferId)
                .build()

        workManager.enqueueUniqueWork("upload_$transferId", ExistingWorkPolicy.KEEP, request)
    }

    override fun enqueueDownload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    DownloadWorker.createInputData(
                        bucketName = bucketName,
                        objectKey = objectKey,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        transferId = transferId,
                    ),
                ).addTag(TransferManager.DOWNLOAD_TAG)
                .addTag(TransferManager.TRANSFER_TAG_PREFIX + transferId)
                .build()

        workManager.enqueueUniqueWork("download_$transferId", ExistingWorkPolicy.KEEP, request)
    }

    override fun launch(
        transferId: String,
        block: suspend () -> Unit,
    ): Job = throw UnsupportedOperationException("WorkManager adapter does not run in-process work")

    override fun cancel(transferId: String) {
        workManager.cancelAllWorkByTag(TransferManager.TRANSFER_TAG_PREFIX + transferId)
    }
}

/**
 * In-process adapter: foreground uploads/downloads, folder downloads and
 * share-upload batches run as coroutines on a TransferManager-owned scope,
 * with a per-transfer Job registry so cancel() reaches them.
 */
internal class InProcessTransferExecutor(
    private val scope: CoroutineScope,
) : TransferExecutor {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Live jobs, exposed for tests. */
    val activeJobs: Map<String, Job>
        get() = jobs

    override fun enqueueUpload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileUri: String,
        contentType: String,
        fileName: String,
    ): Unit = throw UnsupportedOperationException("In-process adapter does not enqueue WorkManager work")

    override fun enqueueDownload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ): Unit = throw UnsupportedOperationException("In-process adapter does not enqueue WorkManager work")

    override fun launch(
        transferId: String,
        block: suspend () -> Unit,
    ): Job {
        val job =
            scope.launch {
                try {
                    block()
                } finally {
                    jobs.remove(transferId)
                }
            }
        jobs[transferId] = job
        // The block may have completed before registration above; drop the
        // stale handle then (transfer ids are unique, never relaunched).
        if (job.isCompleted) jobs.remove(transferId, job)
        return job
    }

    override fun cancel(transferId: String) {
        jobs.remove(transferId)?.cancel()
    }
}

/**
 * Composite: routes enqueue* to WorkManager and launch/cancel to in-process.
 * Cancellation fans out to both — WorkManager cancel by tag tolerates
 * unknown ids, and the in-process registry just misses.
 */
internal class CompositeTransferExecutor(
    private val background: WorkManagerTransferExecutor,
    private val inProcess: InProcessTransferExecutor,
) : TransferExecutor {
    override fun enqueueUpload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileUri: String,
        contentType: String,
        fileName: String,
    ) = background.enqueueUpload(transferId, bucketName, objectKey, fileUri, contentType, fileName)

    override fun enqueueDownload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ) = background.enqueueDownload(transferId, bucketName, objectKey, fileName, fileSize, mimeType)

    override fun launch(
        transferId: String,
        block: suspend () -> Unit,
    ): Job = inProcess.launch(transferId, block)

    override fun cancel(transferId: String) {
        background.cancel(transferId)
        inProcess.cancel(transferId)
    }
}
