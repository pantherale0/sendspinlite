package com.sendspinlite

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages opt-in crash and ANR reporting via Sentry.
 *
 * Privacy: Sentry is only initialised after the user explicitly enables crash reporting.
 * No data is sent unless the user has opted in. The DSN must be configured at build time
 * via the SENTRY_DSN environment variable; if it is empty, crash reporting is unavailable.
 */
object CrashReportingManager {

    private const val TAG = "CrashReportingManager"
    private const val PREFS_NAME = "SendspinPlayerPrefs"
    const val KEY_CRASH_REPORTING_ENABLED = "crash_reporting_enabled"
    private const val CRASH_REPORT_FILE = "sendspin_crash_report.txt"

    /** Returns true if the build was compiled with a Sentry DSN. */
    fun isCrashReportingAvailable(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    /** Returns true if the user has opted in to crash reporting. */
    fun isCrashReportingEnabled(context: Context): Boolean {
        if (!isCrashReportingAvailable()) return false
        return prefs(context).getBoolean(KEY_CRASH_REPORTING_ENABLED, false)
    }

    /**
     * Enable or disable Sentry crash reporting.
     * Enabling initialises Sentry immediately; disabling closes the current SDK instance.
     */
    fun setCrashReportingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CRASH_REPORTING_ENABLED, enabled).apply()
        if (enabled) {
            initSentry(context)
        } else {
            Sentry.close()
            Log.i(TAG, "Crash reporting disabled — Sentry closed")
        }
    }

    /**
     * Call this once at application startup (e.g. from [SendspinApplication]).
     * Sentry is only initialised when the user has already opted in.
     */
    fun initIfEnabled(context: Context) {
        if (isCrashReportingEnabled(context)) {
            initSentry(context)
        }
        installCrashFileHandler(context)
    }

    /** Returns the pending crash report file, or null if there is none. */
    fun getPendingCrashReport(context: Context): File? {
        val file = getCrashReportFile(context)
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Delete the pending crash report file. */
    fun clearPendingCrashReport(context: Context) {
        getCrashReportFile(context).delete()
    }

    /**
     * Send the pending crash report to Sentry (if opted in and Sentry is available),
     * then delete the local file.
     */
    fun sendPendingCrashReport(context: Context) {
        val file = getPendingCrashReport(context) ?: return
        if (!isCrashReportingEnabled(context)) {
            Log.w(TAG, "Cannot send crash report — crash reporting is not enabled")
            return
        }
        try {
            val report = file.readText()
            val event = SentryEvent().apply {
                level = SentryLevel.FATAL
                message = io.sentry.protocol.Message().apply { message = "Crash report" }
                setExtra("crash_report", report)
                setExtra("app_version", BuildConfig.VERSION_NAME)
                setExtra("android_version", Build.VERSION.RELEASE)
                setExtra("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
            Sentry.captureEvent(event)
            Log.i(TAG, "Pending crash report sent to Sentry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send crash report: ${e.message}")
        } finally {
            clearPendingCrashReport(context)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the [File] that crash reports are written to / read from.
     *
     * On Android 10+ the report lands in the app-specific external documents folder
     * (`/sdcard/Android/data/com.sendspinlite/files/Documents/`) which is readable by any
     * file manager without requiring a runtime permission.
     *
     * On Android 9 and below the public Documents folder (`/sdcard/Documents/`) is used when
     * [android.Manifest.permission.WRITE_EXTERNAL_STORAGE] has been granted; otherwise the
     * private internal files directory is used as a fallback.
     */
    private fun getCrashReportFile(context: Context): File {
        val dir: File = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // API 29+: app-specific external dir — no permission required, visible in file manager
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: context.filesDir
            }
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
                    context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED -> {
                // API < 29 with storage permission: public Documents folder on /sdcard
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }
            else -> context.filesDir
        }
        dir.mkdirs()
        return File(dir, CRASH_REPORT_FILE)
    }

    private fun initSentry(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            Log.w(TAG, "SENTRY_DSN is not configured — crash reporting unavailable")
            return
        }
        SentryAndroid.init(context) { options: SentryAndroidOptions ->
            options.dsn = dsn
            options.isEnableAutoSessionTracking = false   // Disable session tracking for privacy
            options.isAnrEnabled = true                   // detect ANR (App Not Responding) events
            options.isAttachScreenshot = false            // do not capture screenshots
            options.isSendDefaultPii = false              // no PII
            options.release = "sendspin-lite@${BuildConfig.VERSION_NAME}"
            options.environment = "production"
        }
        Log.i(TAG, "Sentry initialised (crash + ANR reporting enabled)")
    }

    /**
     * Install a custom [Thread.UncaughtExceptionHandler] that writes a crash report to disk
     * before delegating to the default handler (which terminates the process).
     *
     * This allows the crash info to be shown on the next app launch even if Sentry was not
     * yet enabled when the crash occurred.
     */
    private fun installCrashFileHandler(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReport(appContext, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash report file: ${e.message}")
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== Sendspin Lite Crash Report ===")
        sb.appendLine("Timestamp : $timestamp")
        sb.appendLine("App Version: ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Android   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device    : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Thread    : ${thread.name}")
        sb.appendLine("==================================")
        sb.appendLine()
        sb.appendLine(throwable.stackTraceToString())

        // Collect recent logcat output to include with the report
        try {
            val process = Runtime.getRuntime().exec("logcat -d -t 200")
            val logs = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            process.destroy()
            sb.appendLine()
            sb.appendLine("=== Recent Logcat (last 200 lines) ===")
            sb.appendLine(logs)
        } catch (e: Exception) {
            sb.appendLine("(logcat unavailable: ${e.message})")
        }

        getCrashReportFile(context).writeText(sb.toString())
    }
}
