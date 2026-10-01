package com.notify.core.downloads.storage

import android.content.Context
import android.content.SharedPreferences
import com.notify.core.model.DownloadBucket

/**
 * Manages download-related user preferences.
 */
class DownloadPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "notify_download_prefs"
        private const val KEY_SMART_BUDGET_BYTES = "smart_budget_bytes"
        private const val KEY_FREE_SPACE_RESERVE_BYTES = "free_space_reserve_bytes"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_AUTO_SAVE_ENABLED = "auto_save_enabled"
        private const val KEY_AUTO_SAVE_THRESHOLD_MS = "auto_save_threshold_ms"
        private const val KEY_WIFI_ONLY = "wifi_only"

        /** Default smart offline budget: 1 GB */
        const val DEFAULT_SMART_BUDGET_BYTES = 1_073_741_824L
        /** Default free space reserve: 100 MB */
        const val DEFAULT_FREE_SPACE_RESERVE_BYTES = 104_857_600L
        /** Default auto-save playback threshold: 30 seconds */
        const val DEFAULT_AUTO_SAVE_THRESHOLD_MS = 30_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Maximum bytes for smart offline storage (LRU eviction above this). */
    var smartBudgetBytes: Long
        get() = prefs.getLong(KEY_SMART_BUDGET_BYTES, DEFAULT_SMART_BUDGET_BYTES)
        set(value) = prefs.edit().putLong(KEY_SMART_BUDGET_BYTES, value).apply()

    /** Minimum free space to maintain on the device. Downloads pause below this. */
    var freeSpaceReserveBytes: Long
        get() = prefs.getLong(KEY_FREE_SPACE_RESERVE_BYTES, DEFAULT_FREE_SPACE_RESERVE_BYTES)
        set(value) = prefs.edit().putLong(KEY_FREE_SPACE_RESERVE_BYTES, value).apply()

    /** Selected audio quality profile. */
    var audioQualityProfile: AudioQualityProfile
        get() {
            val name = prefs.getString(KEY_AUDIO_QUALITY, AudioQualityProfile.NORMAL.name)
            return try {
                AudioQualityProfile.valueOf(name ?: AudioQualityProfile.NORMAL.name)
            } catch (_: Exception) {
                AudioQualityProfile.NORMAL
            }
        }
        set(value) = prefs.edit().putString(KEY_AUDIO_QUALITY, value.name).apply()

    /** Auto-save played songs: default ON. */
    var autoSaveEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SAVE_ENABLED, value).apply()

    /** Auto-save playback threshold in milliseconds. */
    var autoSaveThresholdMs: Long
        get() = prefs.getLong(KEY_AUTO_SAVE_THRESHOLD_MS, DEFAULT_AUTO_SAVE_THRESHOLD_MS)
        set(value) = prefs.edit().putLong(KEY_AUTO_SAVE_THRESHOLD_MS, value).apply()

    /** Download only on Wi-Fi. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
}
