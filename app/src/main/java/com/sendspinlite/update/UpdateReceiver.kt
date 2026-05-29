package com.sendspinlite.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

class UpdateReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "UpdateReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Restart into a fresh task after package replacement so Android does not
            // resume stale ActivityRecords from the previous APK process.
            val packageManager: PackageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                try {
                    context.startActivity(launchIntent)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to relaunch app after update: ${e.message}")
                }
            }
        }
    }
}
