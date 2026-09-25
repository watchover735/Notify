package com.notify.download.spike

import android.content.Context
import android.util.Log

/**
 * Idempotent initializer stub (native binaries removed in favor of remote microservice).
 */
object YtDlpInitializer {

    private const val TAG = "YtDlpInitializer"

    @Volatile
    private var isInitialized = true

    fun ensureInitialized(context: Context) {
        Log.d(TAG, "Native yt-dlp replaced by remote microservice. No local binary init required.")
    }

    fun isInitialized(): Boolean = true
}
