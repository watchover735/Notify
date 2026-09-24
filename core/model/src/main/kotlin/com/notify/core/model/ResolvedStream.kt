package com.notify.core.model

/**
 * Ephemeral in-memory representation of a resolved online audio stream.
 * NEVER persist this object to Room database or durable disk storage.
 */
data class ResolvedStream(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val formatId: String? = null,
    val expiresAtEpochMs: Long? = null,
    val videoId: String? = null,
    val mimeType: String? = null,
    val container: String? = null,
    val bitrate: Long? = null,
    val contentLength: Long? = null,
    /**
     * Ordered list of lower-quality fallback stream URLs to try if [streamUrl] fails at playback time
     * (e.g., HTTP 404 when 320kbps CDN variant does not exist).
     * Empty for most providers; populated by JioSaavn resolver with [_160.mp4, _96.mp4] URLs.
     * Checked and retried transparently by PlaybackQueueCoordinator without user-visible error.
     */
    val fallbackUrls: List<String> = emptyList()
) {
    /** Alias for streamUrl for URI callers */
    val uri: String get() = streamUrl

    /** Alias for headers */
    val requiredHttpHeaders: Map<String, String> get() = headers
    /**
     * Checks if this stream URL has expired or will expire within the given safety margin.
     */
    fun isExpired(
        currentEpochMs: Long = System.currentTimeMillis(),
        safetyMarginMs: Long = 60_000L
    ): Boolean {
        val expiration = expiresAtEpochMs ?: return false
        return currentEpochMs >= (expiration - safetyMarginMs)
    }
}
