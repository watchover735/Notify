package com.notify.core.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages locally cached high-resolution album artwork for downloaded tracks.
 * - Stores files on internal storage under `notify_artwork/`.
 * - Provides file:// URIs for offline playback in UI, Coil, and Media3 notification/lock-screen.
 * - Uses atomic write via temporary file to guarantee no corrupted partial images.
 */
object LocalArtworkStore {

    private const val TAG = "LocalArtworkStore"
    private const val ARTWORK_DIR = "notify_artwork"

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightDownloads = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun getArtworkDirectory(context: Context? = appContext): File? {
        val ctx = context ?: appContext ?: return null
        val dir = File(ctx.filesDir, ARTWORK_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sanitizeKey(trackId: String): String {
        return trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }

    fun getArtworkFile(trackId: String, context: Context? = appContext): File? {
        val dir = getArtworkDirectory(context) ?: return null
        val exact = File(dir, "${sanitizeKey(trackId)}.jpg")
        if (exact.exists() && exact.length() > 0L) return exact

        // Check if trackId has prefix (e.g. "youtube:123" -> check "123.jpg")
        if (trackId.contains(":") || trackId.contains("_")) {
            val raw = trackId.substringAfterLast(":")
            val f = File(dir, "${sanitizeKey(raw)}.jpg")
            if (f.exists() && f.length() > 0L) return f
        }

        // Check if directory has any file ending with _${sanitizedKey}.jpg (e.g. "youtube_123.jpg" for "123")
        val sanitized = sanitizeKey(trackId)
        val matches = dir.listFiles { _, name -> name.endsWith("_$sanitized.jpg") }
        if (!matches.isNullOrEmpty()) {
            val candidate = matches.firstOrNull { it.length() > 0L }
            if (candidate != null) return candidate
        }

        return exact
    }

    fun hasArtwork(trackId: String, context: Context? = appContext): Boolean {
        val file = getArtworkFile(trackId, context) ?: return false
        return file.exists() && file.length() > 0L
    }

    fun getArtworkUri(trackId: String, context: Context? = appContext): Uri? {
        val file = getArtworkFile(trackId, context) ?: return null
        return if (file.exists() && file.length() > 0L) {
            Uri.fromFile(file)
        } else {
            null
        }
    }

    fun downloadAndCacheArtworkAsync(
        trackId: String,
        remoteUrl: String?,
        context: Context? = appContext,
        targetPx: Int = 800
    ) {
        if (remoteUrl.isNullOrBlank() || hasArtwork(trackId, context)) return
        downloadScope.launch {
            try {
                downloadAndCacheArtwork(trackId, remoteUrl, context, targetPx)
            } catch (e: Exception) {
                Log.w(TAG, "Async artwork download failed for $trackId: ${e.message}")
            }
        }
    }

    /**
     * Downloads and caches the high-resolution artwork for the given track.
     * Thread-safe, non-blocking, atomic file write.
     */
    suspend fun downloadAndCacheArtwork(
        trackId: String,
        remoteUrl: String?,
        context: Context? = appContext,
        targetPx: Int = 800
    ): Boolean = withContext(Dispatchers.IO) {
        if (remoteUrl.isNullOrBlank()) return@withContext false
        val finalUrl = ArtworkResolution.highResArtwork(remoteUrl, targetPx) ?: remoteUrl
        if (finalUrl.startsWith("file://") || finalUrl.startsWith("content://") || finalUrl.startsWith("/")) {
            return@withContext false
        }

        if (!inFlightDownloads.add(trackId)) {
            Log.d(TAG, "Artwork download already in flight for $trackId, skipping duplicate")
            return@withContext false
        }

        val dir = getArtworkDirectory(context)
        if (dir == null) {
            inFlightDownloads.remove(trackId)
            return@withContext false
        }
        val targetFile = File(dir, "${sanitizeKey(trackId)}.jpg")
        val tempFile = File(dir, "${sanitizeKey(trackId)}.tmp")

        try {
            val url = URL(finalUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() > 0L) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (e: Exception) {
                        try {
                            Files.move(
                                tempFile.toPath(),
                                targetFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (e2: Exception) {
                            Log.w(TAG, "Failed move for $trackId (${e2.message}), falling back to copy", e2)
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }
                    }
                    if (trackId.contains(":")) {
                        val rawId = trackId.substringAfterLast(":")
                        if (rawId.isNotBlank()) {
                            val aliasFile = File(dir, "${sanitizeKey(rawId)}.jpg")
                            try {
                                targetFile.copyTo(aliasFile, overwrite = true)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed copying alias artwork for $rawId: ${e.message}")
                            }
                        }
                    }
                    Log.d(TAG, "Cached HD artwork for $trackId (${targetFile.length()} bytes)")
                    return@withContext true
                } else {
                    tempFile.delete()
                    return@withContext false
                }
            } else {
                Log.w(TAG, "Failed downloading artwork from $finalUrl: HTTP ${connection.responseCode}")
                tempFile.delete()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading artwork for $trackId: ${e.message}")
            if (tempFile.exists()) tempFile.delete()
            return@withContext false
        } finally {
            inFlightDownloads.remove(trackId)
        }
    }
}
