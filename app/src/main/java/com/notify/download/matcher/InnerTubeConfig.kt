package com.notify.download.matcher

/**
 * InnerTubeConfig:
 * Centralized, replaceable client configuration for InnerTube endpoints.
 *
 * Invariants:
 * - Replaceable in one place without touching endpoint parsing or retry logic.
 * - Does not log or duplicate embedded API keys.
 */
data class InnerTubeClientProfile(
    val profileName: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val referer: String = "https://music.youtube.com/",
    val extraHeaders: Map<String, String> = emptyMap(),
    val hl: String = "en",
    val gl: String = "US"
)

object InnerTubeConfig {

    const val BASE_URL = "https://music.youtube.com/youtubei/v1/"
    const val NEXT_PATH = "next"
    const val SEARCH_PATH = "search"

    const val CONNECT_TIMEOUT_SECONDS = 6L
    const val READ_TIMEOUT_SECONDS = 8L

    /**
     * Standard WEB_REMIX profile used by YouTube Music Web client.
     */
    val PROFILE_WEB_REMIX = InnerTubeClientProfile(
        profileName = "WEB_REMIX",
        clientName = "WEB_REMIX",
        clientVersion = "1.20240101.01.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        referer = "https://music.youtube.com/"
    )

    /**
     * Android Music profile variant for testing and fallback evaluation.
     */
    val PROFILE_ANDROID_MUSIC = InnerTubeClientProfile(
        profileName = "ANDROID_MUSIC",
        clientName = "ANDROID_MUSIC",
        clientVersion = "7.27.52",
        userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; en_US) gzip",
        referer = "https://music.youtube.com/"
    )

    /**
     * Currently active profile for WatchNextProvider.
     * Can be swapped dynamically or during probe testing.
     */
    @Volatile
    var activeProfile: InnerTubeClientProfile = PROFILE_WEB_REMIX

    fun nextUrl(): String = "$BASE_URL$NEXT_PATH"
    fun searchUrl(): String = "$BASE_URL$SEARCH_PATH"
}
