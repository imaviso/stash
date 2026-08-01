package com.imaviso.stash.data.transfer

import kotlinx.coroutines.Job

/**
 * Test adapter at the execution seam: records dispatch calls, never touches
 * WorkManager or coroutines. Returned jobs are plain non-running [Job] handles.
 */
internal class FakeTransferExecutor : TransferExecutor {
    data class UploadCall(
        val transferId: String,
        val bucketName: String,
        val objectKey: String,
        val fileUri: String,
        val contentType: String,
        val fileName: String,
    )

    data class DownloadCall(
        val transferId: String,
        val bucketName: String,
        val objectKey: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
    )

    val enqueuedUploads = mutableListOf<UploadCall>()
    val enqueuedDownloads = mutableListOf<DownloadCall>()
    val launchedTransferIds = mutableListOf<String>()
    val cancelledTransferIds = mutableListOf<String>()

    override fun enqueueUpload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileUri: String,
        contentType: String,
        fileName: String,
    ) {
        enqueuedUploads += UploadCall(transferId, bucketName, objectKey, fileUri, contentType, fileName)
    }

    override fun enqueueDownload(
        transferId: String,
        bucketName: String,
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ) {
        enqueuedDownloads += DownloadCall(transferId, bucketName, objectKey, fileName, fileSize, mimeType)
    }

    override fun launch(
        transferId: String,
        block: suspend () -> Unit,
    ): Job {
        launchedTransferIds += transferId
        return Job()
    }

    override fun cancel(transferId: String) {
        cancelledTransferIds += transferId
    }
}
