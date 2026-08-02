package com.sendspinlite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver for external volume control and ducking intents
 * (e.g., from Voice Assistants, Home Assistant Companion, Tasker, or local automation scripts).
 *
 * Registered dynamically by [SendspinService] with [ContextCompat.RECEIVER_EXPORTED] while the
 * service is alive. Manifest registration alone cannot receive these custom actions on modern
 * targetSdk; senders should also setPackage("com.sendspinlite").
 *
 * Supported Actions:
 * - `com.sendspinlite.ACTION_DUCK`
 * - `com.sendspinlite.ACTION_UNDUCK`
 * - `com.sendspinlite.ACTION_SET_APP_VOLUME`
 * - `com.sendspinlite.ACTION_TOGGLE_DUCK`
 */
class SendspinIntentReceiver : BroadcastReceiver() {
    private val tag = "SendspinIntentReceiver"

    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action ?: return
        Log.d(tag, "Received broadcast action: $action")

        val serviceIntent =
            Intent(context, SendspinService::class.java).apply {
                this.action = action
                intent.extras?.let { putExtras(it) }
            }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to forward intent action $action to SendspinService", e)
        }
    }
}
