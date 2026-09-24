package com.notify.core.model

/**
 * Pure utility for normalizing audio provider aliases and source IDs across the application.
 *
 * Invariant: Must remain a pure utility. Zero Room, Android Context, or database dependencies.
 */
object ProviderNormalizer {
    const val YOUTUBE = "youtube"
    const val SPOTIFY = "spotify"
    const val LOCAL = "local"
    const val OFFLINE = "offline"

    /**
     * Normalizes provider strings into canonical lowercase tokens:
     * - "youtube", "youtubemusic", "youtube_music", "yt", "ytm" -> "youtube"
     * - "spotify" -> "spotify"
     */
    fun normalize(provider: String?): String {
        if (provider.isNullOrBlank()) return ""
        return when (provider.trim().lowercase()) {
            "youtube", "youtubemusic", "youtube_music", "yt", "ytm" -> YOUTUBE
            "spotify" -> SPOTIFY
            "local" -> LOCAL
            "offline" -> OFFLINE
            else -> provider.trim().lowercase()
        }
    }

    fun isYouTube(provider: String?): Boolean = normalize(provider) == YOUTUBE
    fun isSpotify(provider: String?): Boolean = normalize(provider) == SPOTIFY

    /**
     * Cleans and normalizes source IDs, extracting 11-char video IDs from YouTube URLs if needed.
     */
    fun normalizeSourceId(provider: String?, sourceId: String?): String {
        if (sourceId.isNullOrBlank()) return ""
        val normProvider = normalize(provider)
        val trimmed = sourceId.trim()
        if (normProvider == YOUTUBE) {
            if (trimmed.contains("v=")) {
                val extracted = trimmed.substringAfter("v=").substringBefore("&").substringBefore("?")
                if (extracted.length == 11) return extracted
            } else if (trimmed.contains("youtu.be/")) {
                val extracted = trimmed.substringAfter("youtu.be/").substringBefore("?").substringBefore("/")
                if (extracted.length == 11) return extracted
            }
        }
        return trimmed
    }
}
