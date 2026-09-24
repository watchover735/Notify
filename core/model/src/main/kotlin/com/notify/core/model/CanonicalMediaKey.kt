package com.notify.core.model

/**
 * Pure utility for deriving stable canonical media keys for deduplication,
 * playback timeline tracking, and centralized Recents recording.
 *
 * Invariant: Must remain a pure utility. Zero Room, Android Context, or database dependencies.
 */
object CanonicalMediaKey {

    /**
     * Derives a canonical key from the resolved playback provider and source identifier.
     * Example: provider="youtube", sourceId="6epn3r7S14K" -> "youtube:6epn3r7S14K"
     */
    fun fromResolved(provider: String, sourceId: String): String {
        val normalizedProvider = ProviderNormalizer.normalize(provider)
        val normalizedSourceId = ProviderNormalizer.normalizeSourceId(normalizedProvider, sourceId)
        return "$normalizedProvider:$normalizedSourceId"
    }

    /**
     * Derives a canonical key from a domain [TrackId].
     */
    fun fromTrackId(trackId: TrackId): String {
        return "${trackId.provider.name.lowercase()}:${trackId.rawId.trim()}"
    }

    /**
     * Derives a canonical key from a domain [Track].
     * Uses the resolved source if available (e.g., Remote YouTube video ID, Local URI, or Offline key).
     */
    fun fromTrack(track: Track): String {
        return when (val source = track.source) {
            is AudioSource.Remote -> fromResolved(source.provider.name, source.sourceId)
            is AudioSource.Local -> fromResolved("local", source.contentUriString)
            is AudioSource.Offline -> fromResolved("offline", source.relativeStorageKey)
        }
    }

    /**
     * Generates a normalized metadata fingerprint for fallback deduplication
     * when provider-specific IDs are unavailable.
     */
    fun fingerprint(title: String, artist: String): String {
        val cleanTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanArtist = artist.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        return "fp:$cleanTitle:$cleanArtist"
    }
}
