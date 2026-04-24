package com.sendspinlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.util.Log

/**
 * Receives [DownloadManager.ACTION_DOWNLOAD_COMPLETE] broadcasts and automatically
 * triggers the APK install UI when the pending Sendspin update download finishes.
 *
 * This receiver is only relevant when the user has enabled auto-install updates
 * ([AutoUpdateManager.isAutoUpdateEnabled] returns true).  It matches the incoming
 * download ID against the one stored by [AutoUpdateManager.startDownload]; mismatches
 * (downloads from other parts of the app or other apps) are silently ignored.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val pendingId = AutoUpdateManager.getPendingDownloadId(context)
        if (downloadId != pendingId) return   // Not our download – ignore.

        Log.i("DownloadCompleteReceiver", "Update download $downloadId complete, launching install")
        AutoUpdateManager.clearPendingDownloadId(context)

        // Only auto-launch the install UI when the user has explicitly enabled auto-install.
        if (AutoUpdateManager.isAutoUpdateEnabled(context)) {
            AutoUpdateManager.installDownloadedApk(context, downloadId)
        }
    }
}
