package com.notify.core.model

/**
 * Identifies the audio provider or extraction source.
 */
enum class ProviderId {
    LOCAL,
    YOUTUBE,
    SPOTIFY,
    CUSTOM_RESOLVER
}

/**
 * Unique and immutable identifier for a track.
 * Decouples provider-specific identifiers from media playback.
 */
data class TrackId(
    val provider: ProviderId,
    val rawId: String
) {
    override fun toString(): String = "${provider.name.lowercase()}:$rawId"

    companion object {
        fun local(uri: String): TrackId = TrackId(ProviderId.LOCAL, uri)
        fun youtube(videoId: String): TrackId = TrackId(ProviderId.YOUTUBE, videoId)
        fun spotify(id: String): TrackId = TrackId(ProviderId.SPOTIFY, id)
        fun custom(id: String): TrackId = TrackId(ProviderId.CUSTOM_RESOLVER, id)
    }
}
