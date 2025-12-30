package com.imaviso.stash.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.imaviso.stash.MainActivity
import com.imaviso.stash.R

/**
 * Manages notifications for background file transfers
 */
class TransferNotificationManager(
    private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "stash_transfers"
        const val CHANNEL_NAME = "File Transfers"
        const val CHANNEL_DESCRIPTION = "Notifications for file uploads and downloads"

        // Base notification IDs - actual IDs are computed from transfer ID hash
        const val UPLOAD_NOTIFICATION_BASE = 2000
        const val DOWNLOAD_NOTIFICATION_BASE = 3000

        // Legacy IDs for backward compatibility
        const val UPLOAD_NOTIFICATION_ID = 1001
        const val DOWNLOAD_NOTIFICATION_ID = 1002

        /**
         * Generate a unique notification ID for a transfer
         */
        fun getNotificationId(
            transferId: String,
            isUpload: Boolean,
        ): Int {
            val base = if (isUpload) UPLOAD_NOTIFICATION_BASE else DOWNLOAD_NOTIFICATION_BASE
            return base + (transferId.hashCode() and 0x7FFFFFFF) % 1000
        }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    setShowBadge(false)
                }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createContentIntent(): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Create a notification for upload progress
     */
    fun createUploadNotification(
        fileName: String,
        progress: Int,
        isIndeterminate: Boolean = false,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading")
            .setContentText(fileName)
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    /**
     * Create a notification for download progress
     */
    fun createDownloadNotification(
        fileName: String,
        progress: Int,
        isIndeterminate: Boolean = false,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(fileName)
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    /**
     * Show upload complete notification with unique ID
     */
    fun showUploadComplete(
        fileName: String,
        success: Boolean,
        transferId: String? = null,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (success) {
                        android.R.drawable.stat_sys_upload_done
                    } else {
                        android.R.drawable.stat_notify_error
                    },
                ).setContentTitle(if (success) "Upload Complete" else "Upload Failed")
                .setContentText(fileName)
                .setContentIntent(createContentIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        val notificationId =
            if (transferId != null) {
                getNotificationId(transferId, true)
            } else {
                UPLOAD_NOTIFICATION_ID + fileName.hashCode()
            }
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Show download complete notification with unique ID
     */
    fun showDownloadComplete(
        fileName: String,
        success: Boolean,
        transferId: String? = null,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (success) {
                        android.R.drawable.stat_sys_download_done
                    } else {
                        android.R.drawable.stat_notify_error
                    },
                ).setContentTitle(if (success) "Download Complete" else "Download Failed")
                .setContentText(fileName)
                .setContentIntent(createContentIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        val notificationId =
            if (transferId != null) {
                getNotificationId(transferId, false)
            } else {
                DOWNLOAD_NOTIFICATION_ID + fileName.hashCode()
            }
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancel a notification
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}
