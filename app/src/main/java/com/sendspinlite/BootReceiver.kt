package com.sendspinlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // We use equals() to avoid a NullPointerException
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Load saved settings from SharedPreferences
            val prefs = context.getSharedPreferences("SendspinPlayerPrefs", Context.MODE_PRIVATE)
            
            // Auto-start on boot if we have a saved server (no opt-in required)
            val savedWsUrl = prefs.getString("ws_url", null)
            if (savedWsUrl.isNullOrBlank()) {
                // No saved server, don't auto-start
                return
            }
            
            val clientName = "Android Player"
            
            // Get or retrieve hardware device ID
            var deviceId = prefs.getString("device_id", null)
            if (deviceId == null) {
                // Use hardware device identifier that's unique per device and persists across reinstalls
                // ANDROID_ID is the standard way for regular apps to get a stable device identifier
                deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
                prefs.edit().putString("device_id", deviceId).apply()
            }

            val serviceIntent = Intent(context, SendspinService::class.java).apply {
                putExtra("wsUrl", savedWsUrl)
                putExtra("clientId", deviceId)
                putExtra("clientName", clientName)
                putExtra("fromBoot", "1")
            }

            // On Android 12+, BOOT_COMPLETED cannot start a foreground service with media session
            // Use regular startService() instead - the service will run in background mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startService(serviceIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
