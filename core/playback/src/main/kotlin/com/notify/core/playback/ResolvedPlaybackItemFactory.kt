package com.notify.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.Track

/**
 * ResolvedPlaybackItemFactory:
 * Constructs ephemeral Media3 MediaItem instances for online streaming playback.
 * Strictly avoids persisting temporary signed stream URLs to Room, TrackEntity, or SharedPreferences.
 */
object ResolvedPlaybackItemFactory {

    const val EXTRA_IS_STREAMING = "com.notify.extra.IS_STREAMING"

    /**
     * Builds an in-memory playable MediaItem for a resolved online stream.
     *
     * @param track The domain [Track] holding stable metadata (title, artist, album, artwork, TrackId).
     * @param streamUrl The temporary, signed direct audio stream URL (e.g., googlevideo CDN).
     */
    fun createMediaItem(
        track: Track,
        streamUrl: String,
        queueEntryId: String? = null,
        canonicalMediaKey: String? = null,
        sessionId: Long? = null,
        sourceContext: String? = null,
        playbackOrigin: PlaybackOrigin = PlaybackOrigin.UNKNOWN,
        formatId: String? = null,
        fallbackUrls: List<String> = emptyList()
    ): MediaItem {
        val namespacedId = MediaItemMapper.buildNamespacedMediaId(track.id)
        val finalMediaId = queueEntryId ?: namespacedId
        val canonicalKey = canonicalMediaKey ?: "${track.id.provider.name.lowercase()}:${track.id.rawId}"

        val extras = Bundle().apply {
            putString(MediaItemMapper.EXTRA_PROVIDER, track.id.provider.name)
            putString(MediaItemMapper.EXTRA_RAW_ID, track.id.rawId)
            putLong(MediaItemMapper.EXTRA_DURATION_MS, track.durationMs)
            putBoolean(MediaItemMapper.EXTRA_IS_EXPLICIT, track.isExplicit)
            putBoolean(EXTRA_IS_STREAMING, true)
            putString(MediaItemMapper.EXTRA_CANONICAL_MEDIA_KEY, canonicalKey)
            putString(MediaItemMapper.EXTRA_PLAYBACK_ORIGIN, playbackOrigin.name)
            if (queueEntryId != null) {
                putString(MediaItemMapper.EXTRA_QUEUE_ENTRY_ID, queueEntryId)
            }
            if (sessionId != null) {
                putLong(MediaItemMapper.EXTRA_SESSION_ID, sessionId)
            }
            if (sourceContext != null) {
                putString(MediaItemMapper.EXTRA_SOURCE_CONTEXT, sourceContext)
            }
            if (fallbackUrls.isNotEmpty()) {
                // Store as pipe-separated string; pipes are not valid in URLs so no escaping needed.
                putString(MediaItemMapper.EXTRA_FALLBACK_URLS, fallbackUrls.joinToString("|"))
            }
            // Invariant: The temporary signed stream URL is NOT put in EXTRA_CONTENT_URI to avoid accidental persistence.
        }

        val localArtUri = LocalArtworkStore.getArtworkUri(track.id.rawId)
        val highResArtUri = localArtUri?.toString() ?: ArtworkResolution.highResArtwork(track.artworkUri, targetPx = 800)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(highResArtUri?.let { Uri.parse(it) })
            .setExtras(extras)
            .build()

        val customCacheKey = Media3StreamCache.buildCacheKey(
            videoId = track.id.rawId,
            formatId = formatId
        )

        return MediaItem.Builder()
            .setMediaId(finalMediaId)
            .setUri(Uri.parse(streamUrl))
            .setCustomCacheKey(customCacheKey)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(streamUrl))
                    .build()
            )
            .setMediaMetadata(metadata)
            .build()
    }
}
