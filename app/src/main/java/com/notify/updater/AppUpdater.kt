package com.notify.updater

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.notify.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Data model for a release asset.
 */
data class ApkAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long
)

/**
 * Information regarding an available update parsed from GitHub Releases.
 */
data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val apkAsset: ApkAsset?
)

/**
 * GitHub-Releases-based in-app auto-updater for NotiFy.
 *
 * Requirements:
 * - Checks GET https://api.github.com/repos/watchover735/Notify/releases/latest in background.
 * - Semantic version comparison (e.g. v1.0.10 > v1.0.9).
 * - Fails silently on network errors, rate limiting, missing releases, or missing tags.
 * - Shows update dialog with changelog, "Update Now", and "Later".
 * - "Later" dismisses and suppresses prompts for the remainder of the session.
 * - Downloads APK using [DownloadManager] into external files downloads directory.
 * - Prevents re-downloading if the update APK is already present and complete.
 * - Launches installer using secure [FileProvider] content:// URI.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"

    // Single source of truth for repository release endpoint
    const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/watchover735/Notify/releases/latest"

    private const val PREFS_NAME = "notify_updater_prefs"
    private const val KEY_ACTIVE_DOWNLOAD_ID = "active_download_id"
    private const val KEY_PENDING_APK_PATH = "pending_apk_path"
    private const val KEY_PENDING_VERSION = "pending_version"

    // Session-only flags: reset on process kill / new app session
    @Volatile
    private var hasCheckedThisSession = false
    @Volatile
    private var hasDismissedThisSession = false

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Checks for updates asynchronously on [ioDispatcher] (background, non-blocking).
     * Does not block app launch or UI rendering.
     * Fails silently on network errors, rate limits, or if already up-to-date.
     */
    fun checkForUpdates(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME,
        forceCheck: Boolean = false,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        if (hasCheckedThisSession && !forceCheck) {
            return
        }
        if (hasDismissedThisSession && !forceCheck) {
            return
        }
        hasCheckedThisSession = true

        val appContext = context.applicationContext
        CoroutineScope(ioDispatcher).launch {
            try {
                val request = Request.Builder()
                    .url(GITHUB_LATEST_RELEASE_URL)
                    .header("User-Agent", "NotiFy/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    // Silently fail on 404 (no releases), 403 (rate-limit), 5xx, etc.
                    Log.d(TAG, "GitHub releases request returned HTTP ${response.code}")
                    return@launch
                }

                val bodyStr = response.body?.string() ?: return@launch
                val json = JSONObject(bodyStr)

                val tagName = json.optString("tag_name")
                if (tagName.isNullOrBlank()) {
                    Log.d(TAG, "GitHub release response has no valid tag_name")
                    return@launch
                }

                // Check semantic version comparison
                if (!SemanticVersion.isNewer(tagName, currentVersion)) {
                    Log.d(TAG, "App is up to date ($currentVersion >= $tagName)")
                    return@launch
                }

                // Newer version found: extract changelog and APK asset
                val changelog = json.optString("body", "").trim()
                val assets = json.optJSONArray("assets")

                var apkAsset: ApkAsset? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val assetObj = assets.optJSONObject(i) ?: continue
                        val name = assetObj.optString("name", "")
                        val downloadUrl = assetObj.optString("browser_download_url", "")
                        val size = assetObj.optLong("size", 0L)

                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                            val asset = ApkAsset(name = name, downloadUrl = downloadUrl, size = size)
                            // Prefer universal APK if available
                            if (name.contains("universal", ignoreCase = true)) {
                                apkAsset = asset
                                break
                            }
                            if (apkAsset == null) {
                                apkAsset = asset
                            }
                        }
                    }
                }

                val updateInfo = UpdateInfo(
                    versionName = tagName,
                    changelog = changelog,
                    apkAsset = apkAsset
                )

                if (!hasDismissedThisSession) {
                    _updateAvailable.value = updateInfo
                }
            } catch (e: Exception) {
                // Silently handle offline/no-internet or parsing errors
                Log.d(TAG, "Update check failed silently: ${e.message}")
            }
        }
    }

    /**
     * User pressed "Later" or dismissed dialog.
     * Marks session as dismissed so user is not nagged again until next app start.
     */
    fun onLaterClicked() {
        hasDismissedThisSession = true
        _updateAvailable.value = null
    }

    /**
     * User pressed "Update Now".
     * Checks if APK is already downloaded, otherwise starts DownloadManager.
     */
    fun onUpdateNowClicked(context: Context, updateInfo: UpdateInfo) {
        val appContext = context.applicationContext
        _updateAvailable.value = null

        val apkAsset = updateInfo.apkAsset
        if (apkAsset == null) {
            postToast(appContext, "No APK asset found for this release.")
            Log.w(TAG, "Release ${updateInfo.versionName} has no .apk asset.")
            return
        }

        val cleanVersion = updateInfo.versionName.removePrefix("v").removePrefix("V")
        val fileName = "notify-update-$cleanVersion.apk"
        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir == null) {
            postToast(appContext, "Downloads storage is unavailable.")
            return
        }

        val targetApk = File(downloadDir, fileName)

        // 1. Check if an APK already exists and is complete (do NOT re-download)
        if (targetApk.exists() && targetApk.length() > 0) {
            val expectedSize = apkAsset.size
            if (expectedSize <= 0 || targetApk.length() == expectedSize) {
                Log.d(TAG, "Update APK already downloaded and verified. Launching install.")
                installApk(appContext, targetApk)
                return
            } else {
                // Incomplete or mismatched size: clean up corrupted file
                targetApk.delete()
            }
        }

        // 2. Clean up any older downloaded update APKs
        downloadDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("notify-update-") && file.name != targetApk.name) {
                file.delete()
            }
        }

        // 3. Avoid duplicate downloads if already running
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            postToast(appContext, "DownloadManager service is unavailable.")
            return
        }

        val activeId = prefs.getLong(KEY_ACTIVE_DOWNLOAD_ID, -1L)
        if (activeId != -1L) {
            try {
                val query = DownloadManager.Query().setFilterById(activeId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusCol != -1) {
                        val status = cursor.getInt(statusCol)
                        if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                            cursor.close()
                            postToast(appContext, "Update download is already in progress...")
                            return
                        }
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking active download: ${e.message}")
            }
        }

        // 4. Enqueue download
        try {
            val request = DownloadManager.Request(Uri.parse(apkAsset.downloadUrl)).apply {
                setTitle("NotiFy Update ${updateInfo.versionName}")
                setDescription("Downloading latest release...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
                setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val newDownloadId = downloadManager.enqueue(request)
            prefs.edit()
                .putLong(KEY_ACTIVE_DOWNLOAD_ID, newDownloadId)
                .putString(KEY_PENDING_APK_PATH, targetApk.absolutePath)
                .putString(KEY_PENDING_VERSION, updateInfo.versionName)
                .apply()

            postToast(appContext, "Downloading update in background...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download via DownloadManager", e)
            postToast(appContext, "Failed to start update download: ${e.message}")
        }
    }

    /**
     * Securely triggers the system package installer prompt using FileProvider content:// URI.
     * Never uses raw file:// URI to prevent FileUriExposedException on modern Android.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "Cannot launch installer: file does not exist at ${apkFile.absolutePath}")
            return
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            postToast(context, "Unable to launch installer: ${e.message}")
        }
    }

    /**
     * Resolves pending update file for a completed download ID.
     */
    fun getPendingApkFile(context: Context, downloadId: Long): File? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(KEY_ACTIVE_DOWNLOAD_ID, -1L)
        if (savedId == downloadId) {
            val path = prefs.getString(KEY_PENDING_APK_PATH, null) ?: return null
            return File(path)
        }
        return null
    }

    /**
     * Helper to show an Android native AlertDialog on an Activity, if used outside Compose.
     */
    fun showNativeDialog(activity: Activity, updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        activity.runOnUiThread {
            if (hasDismissedThisSession) return@runOnUiThread

            val message = if (updateInfo.changelog.isNotBlank()) {
                updateInfo.changelog
            } else {
                "A new version (${updateInfo.versionName}) is available. Would you like to update now?"
            }

            AlertDialog.Builder(activity)
                .setTitle("Update Available: ${updateInfo.versionName}")
                .setMessage(message)
                .setPositiveButton("Update Now") { dialog, _ ->
                    dialog.dismiss()
                    onUpdateNowClicked(activity, updateInfo)
                }
                .setNegativeButton("Later") { dialog, _ ->
                    dialog.dismiss()
                    onLaterClicked()
                }
                .setOnCancelListener {
                    onLaterClicked()
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun postToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Resets session state for testing purposes.
     */
    fun resetSessionForTesting() {
        hasCheckedThisSession = false
        hasDismissedThisSession = false
        _updateAvailable.value = null
    }
}
