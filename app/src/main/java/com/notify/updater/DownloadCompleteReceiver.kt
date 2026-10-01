package com.notify.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Listens for completion of the update APK download triggered by [AppUpdater].
 * Automatically triggers the installation prompt via FileProvider.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        when (action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId == -1L) return

                val apkFile = AppUpdater.getPendingApkFile(context, downloadId) ?: return

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
                val query = DownloadManager.Query().setFilterById(downloadId)
                try {
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusCol != -1) {
                            val status = cursor.getInt(statusCol)
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                Log.d("DownloadReceiver", "Update APK download completed successfully. Launching installer.")
                                AppUpdater.installApk(context, apkFile)
                            } else {
                                Log.w("DownloadReceiver", "Update APK download finished with status $status")
                            }
                        }
                        cursor.close()
                    }
                } catch (e: Exception) {
                    Log.e("DownloadReceiver", "Error querying completed download", e)
                }
            }

            DownloadManager.ACTION_NOTIFICATION_CLICKED -> {
                // If user clicks the download notification after complete, check if we have a pending APK and launch install
                val downloadIds = intent.getLongArrayExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS)
                if (downloadIds != null) {
                    for (id in downloadIds) {
                        val apkFile = AppUpdater.getPendingApkFile(context, id)
                        if (apkFile != null && apkFile.exists()) {
                            AppUpdater.installApk(context, apkFile)
                            break
                        }
                    }
                }
            }
        }
    }
}
