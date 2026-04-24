package com.sendspinlite

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages optional automatic update checking and installation from GitHub releases.
 *
 * On first launch the user is asked whether they want auto-updates.  If they opt in,
 * the app requests the REQUEST_INSTALL_PACKAGES permission (Android 8+) and will
 * automatically download and offer to install new releases found on GitHub.
 * If the user declines auto-install, updates are still detected and a banner is shown
 * in the UI – no extra permissions are required for that path.
 *
 * Checks are rate-limited to at most once every [CHECK_INTERVAL_MS] (7 days).
 */
object AutoUpdateManager {

    private const val TAG = "AutoUpdateManager"
    private const val PREFS_NAME = "SendspinPlayerPrefs"

    const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    const val KEY_AUTO_UPDATE_ASKED = "auto_update_asked"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"

    /** 7 days in milliseconds. */
    private const val CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/pantherale0/sendspinlite/releases/latest"

    // -------------------------------------------------------------------------
    // Public data types
    // -------------------------------------------------------------------------

    data class UpdateInfo(
        /** Full tag name from GitHub, e.g. "v1.8". */
        val tagName: String,
        /** Version string with leading "v" stripped, e.g. "1.8". */
        val versionName: String,
        /** Direct APK download URL, or null if no APK asset is attached to the release. */
        val apkUrl: String?,
        /** HTML URL of the release page for display purposes. */
        val releaseUrl: String
    )

    // -------------------------------------------------------------------------
    // Preference helpers
    // -------------------------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true if the user has opted in to automatic downloading and installing of updates. */
    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, false)

    /** Returns true if the first-launch auto-update prompt has already been shown. */
    fun hasAskedAboutAutoUpdate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_ASKED, false)

    /**
     * Persist the user's choice.
     * Also marks the prompt as having been shown so it is not displayed again.
     */
    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled)
            .putBoolean(KEY_AUTO_UPDATE_ASKED, true)
            .apply()
    }

    /**
     * Mark the first-launch prompt as shown without enabling auto-install.
     * Use this when the user taps "Skip" / "Notify Only".
     */
    fun markAutoUpdateAsked(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_UPDATE_ASKED, true)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Update check
    // -------------------------------------------------------------------------

    /** Returns true if enough time has passed since the last check to warrant a new one. */
    fun shouldCheckForUpdate(context: Context): Boolean {
        val lastCheck = prefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS
    }

    /**
     * Query the GitHub releases API for the latest release.
     *
     * Returns an [UpdateInfo] if a newer version is available, or null if the app is
     * already up to date (or the check fails).  Records the check timestamp on every call
     * regardless of outcome.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        // Always record the check time so we back off even on network errors.
        prefs(context).edit()
            .putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())
            .apply()

        return@withContext try {
            val connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val response = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val versionName = tagName.trimStart('v')
            val releaseUrl = json.getString("html_url")

            if (!isNewerVersion(remote = versionName, current = BuildConfig.VERSION_NAME)) {
                Log.d(TAG, "App is up to date (remote=$versionName, local=${BuildConfig.VERSION_NAME})")
                return@withContext null
            }

            // Find the first APK asset in the release.
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }

            Log.i(TAG, "Update available: $tagName (apk=${apkUrl != null})")
            UpdateInfo(tagName, versionName, apkUrl, releaseUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Download and install
    // -------------------------------------------------------------------------

    /**
     * Enqueue an APK download via [DownloadManager].
     *
     * @return the DownloadManager download ID, or -1 if the download could not be enqueued.
     */
    fun startDownload(context: Context, updateInfo: UpdateInfo): Long {
        val apkUrl = updateInfo.apkUrl ?: run {
            Log.w(TAG, "No APK URL available for ${updateInfo.tagName}")
            return -1L
        }
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("Sendspin Lite Update")
                setDescription("Downloading update ${updateInfo.tagName}…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "sendspin-lite-${updateInfo.tagName}.apk"
                )
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadId = dm.enqueue(request)
            // Persist the pending download ID so DownloadCompleteReceiver can match it.
            prefs(context).edit().putLong(KEY_PENDING_DOWNLOAD_ID, downloadId).apply()
            Log.i(TAG, "Download enqueued (id=$downloadId) for ${updateInfo.tagName}")
            downloadId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download: ${e.message}")
            -1L
        }
    }

    /** Returns the download ID stored by [startDownload], or -1 if none is pending. */
    fun getPendingDownloadId(context: Context): Long =
        prefs(context).getLong(KEY_PENDING_DOWNLOAD_ID, -1L)

    /** Clear the stored pending download ID after install has been triggered. */
    fun clearPendingDownloadId(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_DOWNLOAD_ID).apply()
    }

    /**
     * Trigger the system APK install UI for a completed download.
     *
     * [DownloadManager.COLUMN_LOCAL_URI] returns a `file://` URI.  On Android 7+ (API 24+)
     * passing a `file://` URI to another process via an Intent throws
     * [android.os.FileUriExposedException], so we convert it to a `content://` URI using
     * [FileProvider] before building the install intent.
     */
    fun installDownloadedApk(context: Context, downloadId: Long) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val localUriString = dm.query(query).use { cursor ->
                if (!cursor.moveToFirst()) {
                    Log.w(TAG, "Download ID $downloadId not found in DownloadManager")
                    return
                }
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusCol)
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Log.w(TAG, "Download $downloadId status is $status, not installing")
                    return
                }
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                cursor.getString(uriCol)
            }

            // COLUMN_LOCAL_URI is a file:// URI; convert to content:// via FileProvider so
            // the installer activity (in another process) can read the file on API 24+.
            val filePath = Uri.parse(localUriString).path ?: run {
                Log.e(TAG, "Could not resolve local file path from URI: $localUriString")
                return
            }
            val localFile = File(filePath)
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                localFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
            Log.i(TAG, "Install intent launched for download $downloadId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch install intent: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true when [remote] is a strictly higher version than [current].
     * Compares dot-separated integer segments, e.g. "1.10" > "1.9".
     *
     * Note: non-numeric suffixes (e.g. "-beta", "-rc1") are silently treated as 0.
     * This is intentional — release APKs are expected to use plain numeric versions
     * like "1.8" or "1.8.1".
     */
    internal fun isNewerVersion(remote: String, current: String): Boolean {
        return try {
            val r = remote.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val len = maxOf(r.size, c.size)
            for (i in 0 until len) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv > cv) return true
                if (rv < cv) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
