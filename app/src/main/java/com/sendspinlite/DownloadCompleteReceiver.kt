package com.sendspinlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.util.Log

/**
 * Receives [DownloadManager.ACTION_DOWNLOAD_COMPLETE] broadcasts and triggers the APK
 * install UI when the pending Sendspin update download finishes.
 *
 * A stored pending download ID is the proof that an install was requested — either by the
 * auto-update scheduler or by the user tapping "Download & Install" in the update banner.
 * In both cases the install should proceed, so no further preference check is needed here.
 * Mismatched download IDs (from other parts of the app or other apps) are silently ignored.
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
        AutoUpdateManager.installDownloadedApk(context, downloadId)
    }
}
