package com.notify.download.spike

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Thread-safe, idempotent initializer for YoutubeDL and FFmpeg native binaries.
 * Ensures yt-dlp runtime is initialized before any search, stream resolution, or download execution.
 */
object YtDlpInitializer {

    private const val TAG = "YtDlpInitializer"

    @Volatile
    private var isInitialized = false
    private val lock = Any()

    /**
     * Initializes YoutubeDL and FFmpeg runtimes if not already initialized.
     * Safe to call repeatedly from any thread.
     */
    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            synchronized(lock) {
                if (!isInitialized) {
                    val appContext = context.applicationContext
                    try {
                        Log.i(TAG, "Initializing YoutubeDL and FFmpeg runtimes...")
                        YoutubeDL.getInstance().init(appContext)
                        FFmpeg.getInstance().init(appContext)
                        isInitialized = true
                        Log.i(TAG, "YoutubeDL and FFmpeg runtimes successfully initialized.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize YoutubeDL / FFmpeg: ${e.message}", e)
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Checks if runtimes are already initialized.
     */
    fun isInitialized(): Boolean = isInitialized
}
