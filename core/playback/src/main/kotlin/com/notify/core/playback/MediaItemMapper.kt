package com.notify.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId

/**
 * Lossless round-trip mapper between domain [Track] and Media3 [MediaItem].
 * Uses namespaced MediaItem IDs and MediaMetadata extras to preserve complete track attributes.
 */
object MediaItemMapper {

    const val EXTRA_PROVIDER = "com.notify.extra.PROVIDER"
    const val EXTRA_RAW_ID = "com.notify.extra.RAW_ID"
    const val EXTRA_CONTENT_URI = "com.notify.extra.CONTENT_URI"
    const val EXTRA_DURATION_MS = "com.notify.extra.DURATION_MS"
    const val EXTRA_IS_EXPLICIT = "com.notify.extra.IS_EXPLICIT"
    const val EXTRA_QUEUE_ENTRY_ID = "com.notify.extra.QUEUE_ENTRY_ID"
    const val EXTRA_CANONICAL_MEDIA_KEY = "com.notify.extra.CANONICAL_MEDIA_KEY"
    const val EXTRA_SESSION_ID = "com.notify.extra.SESSION_ID"
    const val EXTRA_SOURCE_CONTEXT = "com.notify.extra.SOURCE_CONTEXT"
    const val EXTRA_PLAYBACK_ORIGIN = "com.notify.extra.PLAYBACK_ORIGIN"
    /** Pipe-separated fallback stream URLs for quality downgrade retry (JioSaavn only). */
    const val EXTRA_FALLBACK_URLS = "com.notify.extra.FALLBACK_URLS"

    /**
     * Extracts the stable queueEntryId from MediaItem metadata or mediaId.
     */
    fun getQueueEntryId(mediaItem: MediaItem?): String? {
        if (mediaItem == null) return null
        return mediaItem.mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY_ID)
            ?: mediaItem.mediaId.takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts ordered fallback stream URLs for quality downgrade retry.
     * Only populated for JioSaavn tracks; empty list for all other providers.
     */
    fun getFallbackUrls(mediaItem: MediaItem?): List<String> {
        if (mediaItem == null) return emptyList()
        val raw = mediaItem.mediaMetadata.extras?.getString(EXTRA_FALLBACK_URLS)
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    /**
     * Extracts the canonicalMediaKey from MediaItem metadata extras.
     * Falls back to parsing namespaced mediaId or ProviderId:rawId if extras are unavailable.
     */
    fun getCanonicalMediaKey(mediaItem: MediaItem?): String? {
        if (mediaItem == null) return null
        val fromExtras = mediaItem.mediaMetadata.extras?.getString(EXTRA_CANONICAL_MEDIA_KEY)
        if (!fromExtras.isNullOrBlank()) return fromExtras
        val mediaId = mediaItem.mediaId
        if (mediaId.contains(":")) {
            val parts = mediaId.split(":", limit = 2)
            val provider = parts[0].lowercase()
            if (provider in listOf("youtube", "spotify", "local", "offline")) {
                return "$provider:${parts[1]}"
            }
        }
        return null
    }

    /**
     * Extracts the playback sessionId from MediaItem metadata extras.
     */
    fun getSessionId(mediaItem: MediaItem?): Long? {
        if (mediaItem == null) return null
        return if (mediaItem.mediaMetadata.extras?.containsKey(EXTRA_SESSION_ID) == true) {
            mediaItem.mediaMetadata.extras?.getLong(EXTRA_SESSION_ID)
        } else null
    }

    /**
     * Extracts the PlaybackOrigin from MediaItem metadata extras.
     * Defaults to PlaybackOrigin.UNKNOWN if missing or unrecognized.
     */
    fun getPlaybackOrigin(mediaItem: MediaItem?): PlaybackOrigin {
        val originStr = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_PLAYBACK_ORIGIN)
        return if (originStr != null) {
            try {
                PlaybackOrigin.valueOf(originStr)
            } catch (_: IllegalArgumentException) {
                PlaybackOrigin.UNKNOWN
            }
        } else {
            PlaybackOrigin.UNKNOWN
        }
    }

    /**
     * Builds a stable, collision-free namespaced mediaId: "${provider.name}:${rawId}".
     */
    fun buildNamespacedMediaId(trackId: TrackId): String {
        return "${trackId.provider.name}:${trackId.rawId}"
    }

    /**
     * Parses a namespaced mediaId into its constituent ProviderId and rawId.
     */
    fun parseNamespacedMediaId(namespacedId: String): Pair<ProviderId, String> {
        val colonIndex = namespacedId.indexOf(':')
        if (colonIndex <= 0 || colonIndex == namespacedId.length - 1) {
            return ProviderId.LOCAL to namespacedId
        }
        val providerStr = namespacedId.substring(0, colonIndex)
        val rawId = namespacedId.substring(colonIndex + 1)
        val provider = try {
            ProviderId.valueOf(providerStr)
        } catch (_: IllegalArgumentException) {
            ProviderId.LOCAL
        }
        return provider to rawId
    }

    /**
     * Converts a domain [Track] into a Media3 [MediaItem].
     * Enforces local source requirement in Phase 3 and preserves content URIs without raw paths.
     */
    fun toMediaItem(
        track: Track,
        queueEntryId: String? = null,
        canonicalMediaKey: String? = null,
        sessionId: Long? = null,
        sourceContext: String? = null,
        playbackOrigin: PlaybackOrigin = PlaybackOrigin.UNKNOWN
    ): MediaItem {
        val localSource = track.source as? AudioSource.Local
            ?: throw IllegalArgumentException("Only AudioSource.Local is playable in Phase 3. Found: ${track.source}")

        val contentUriString = localSource.contentUriString
        val namespacedId = buildNamespacedMediaId(track.id)
        val finalMediaId = queueEntryId ?: namespacedId
        val canonicalKey = canonicalMediaKey ?: "${track.id.provider.name.lowercase()}:${track.id.rawId}"

        val extras = Bundle().apply {
            putString(EXTRA_PROVIDER, track.id.provider.name)
            putString(EXTRA_RAW_ID, track.id.rawId)
            putString(EXTRA_CONTENT_URI, contentUriString)
            putLong(EXTRA_DURATION_MS, track.durationMs)
            putBoolean(EXTRA_IS_EXPLICIT, track.isExplicit)
            putString(EXTRA_CANONICAL_MEDIA_KEY, canonicalKey)
            putString(EXTRA_PLAYBACK_ORIGIN, playbackOrigin.name)
            if (queueEntryId != null) {
                putString(EXTRA_QUEUE_ENTRY_ID, queueEntryId)
            }
            if (sessionId != null) {
                putLong(EXTRA_SESSION_ID, sessionId)
            }
            if (sourceContext != null) {
                putString(EXTRA_SOURCE_CONTEXT, sourceContext)
            }
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

        return MediaItem.Builder()
            .setMediaId(finalMediaId)
            .setUri(Uri.parse(contentUriString))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(contentUriString)).build())
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Losslessly reconstructs a domain [Track] from a Media3 [MediaItem].
     */
    fun fromMediaItem(mediaItem: MediaItem): Track? {
        val extras = mediaItem.mediaMetadata.extras

        val (provider, rawId) = if (extras != null && extras.containsKey(EXTRA_PROVIDER) && extras.containsKey(EXTRA_RAW_ID)) {
            val p = try {
                ProviderId.valueOf(extras.getString(EXTRA_PROVIDER)!!)
            } catch (_: Exception) {
                ProviderId.LOCAL
            }
            p to (extras.getString(EXTRA_RAW_ID) ?: "")
        } else {
            parseNamespacedMediaId(mediaItem.mediaId)
        }

        val contentUriString = extras?.getString(EXTRA_CONTENT_URI)
            ?: mediaItem.requestMetadata.mediaUri?.toString()
            ?: mediaItem.localConfiguration?.uri?.toString()
            ?: rawId

        val durationMs = extras?.getLong(EXTRA_DURATION_MS, 0L) ?: 0L
        val isExplicit = extras?.getBoolean(EXTRA_IS_EXPLICIT, false) ?: false

        val title = mediaItem.mediaMetadata.title?.toString()?.ifEmpty { null } ?: "Unknown Title"
        val artist = mediaItem.mediaMetadata.artist?.toString()?.ifEmpty { null } ?: "Unknown Artist"
        val album = mediaItem.mediaMetadata.albumTitle?.toString()
        val artworkUri = mediaItem.mediaMetadata.artworkUri?.toString()

        val isStreaming = extras?.getBoolean(ResolvedPlaybackItemFactory.EXTRA_IS_STREAMING, false) ?: false
        val source = if (isStreaming) {
            AudioSource.Remote(provider, rawId)
        } else {
            AudioSource.Local(contentUriString)
        }

        return Track(
            id = TrackId(provider, rawId),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUri = artworkUri,
            source = source,
            isExplicit = isExplicit
        )
    }
}
