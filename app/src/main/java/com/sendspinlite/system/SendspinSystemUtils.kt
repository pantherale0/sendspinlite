package com.sendspinlite.system

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import com.sendspinlite.BuildConfig
import android.util.Log

object SendspinSystemUtils {
    fun checkIsLowMemoryDevice(context: Context, tag: String): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L // Less than 2GB total RAM
            if (lowMemory) {
                Log.i(tag, "Low-memory device detected: disabling metadata and action buttons")
            }
            lowMemory
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device memory", e)
            false
        }
    }

    fun getConnectionType(context: Context, tag: String): String {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    when {
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                        else -> "Other"
                    }
                } else {
                    "Unknown"
                }
            } else {
                "Disconnected"
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to get connection type", e)
            "Unknown"
        }
    }

    fun getActualSystemVolume(context: Context, tag: String): Int {
        return try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume =
                audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            (currentVolume * 100 / maxVolume).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(tag, "Failed to get system volume", e)
            100 // Default to max if we can't read
        }
    }

    fun getCurrentVersionCode(context: Context, tag: String): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to resolve app version code, falling back to BuildConfig", e)
            BuildConfig.VERSION_CODE.toLong()
        }
    }
}
