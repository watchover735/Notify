package com.notify.download.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ResolutionState {
    METADATA_ONLY,
    SEARCHING,
    MATCHED,
    NEEDS_REVIEW,
    RESOLVE_FAILED
}

enum class DownloadState {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    CANCELLED
}

/**
 * Tracks the origin of a track's artwork so legacy-repair logic and UI can
 * distinguish genuine per-track art from playlist-cover fallbacks.
 */
object ArtworkOrigin {
    const val UNKNOWN = "UNKNOWN"
    const val SPOTIFY_TRACK = "SPOTIFY_TRACK"         // came from per-track Spotify embed JSON
    const val YOUTUBE_MATCH = "YOUTUBE_MATCH"          // came from a matched YouTube candidate thumbnail
    const val YOUTUBE_ENRICHMENT = "YOUTUBE_ENRICHMENT" // came from background artwork pre-enrichment
    const val PLAYLIST_FALLBACK_LEGACY = "PLAYLIST_FALLBACK_LEGACY" // was a stale playlist-cover copy (cleared)
}

/**
 * Tracks the status of background artwork enrichment for this track.
 */
object ArtworkEnrichmentStatus {
    const val PENDING = "PENDING"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val ENRICHED = "ENRICHED"
    const val NO_CONFIDENT_MATCH = "NO_CONFIDENT_MATCH"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
}

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** 0L signals "unknown duration" – treat as not-yet-known, not as silence. */
    val durationMs: Long = 0L,
    val artworkUri: String? = null,
    val artworkUrl: String? = null,
    val spotifyId: String? = null,
    val resolutionState: ResolutionState = ResolutionState.METADATA_ONLY,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val localContentUri: String? = null,
    val dateAddedEpochMs: Long = System.currentTimeMillis(),
    /** Added in schema v3. Tracks where this equals PLAYLIST_FALLBACK_LEGACY had stale playlist art
     *  and were cleaned up by MIGRATION_2_3. */
    val artworkOrigin: String = ArtworkOrigin.UNKNOWN,
    /** Added in schema v4. Persists enrichment state to prevent endless retries. Canonical initial state is PENDING. */
    val artworkEnrichmentStatus: String = ArtworkEnrichmentStatus.PENDING,
    val lastArtworkAttemptEpochMs: Long = 0L,
    /** Added in schema v5. Number of times enrichment was attempted. */
    val artworkAttemptCount: Int = 0
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val playlistId: String,
    val title: String,
    val sourceUrl: String? = null,
    val artworkUri: String? = null,
    val dateCreatedEpochMs: Long = System.currentTimeMillis(),
    val dateModifiedEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId", "position"], unique = true),
        Index(value = ["playlistId", "trackId"], unique = true),
        Index(value = ["trackId"])
    ]
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val entryId: Long = 0,
    val playlistId: String,
    val trackId: String,
    val position: Int
)

@Entity(
    tableName = "track_sources",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trackId"])]
)
data class TrackSourceEntity(
    @PrimaryKey
    val sourceKey: String,
    val trackId: String,
    val provider: String,
    val sourceId: String,
    val canonicalUrl: String,
    val confidence: Float,
    val durationDeltaMs: Long,
    val artworkUrl: String? = null,
    val selected: Boolean = true
)

/** Search history entry. One row per distinct normalised query; useCount grows on repeats. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    val normalizedQuery: String,
    val displayQuery: String,
    val lastSearchedAtEpochMs: Long,
    val useCount: Int = 1
)

/**
 * Persisted InnerTube/yt-dlp candidate cache.
 * TTL = 7 days. No signed stream URLs or auth cookies are ever stored here.
 * Primary keys are (normalizedQuery, resultRank) so each query has an ordered list.
 */
@Entity(
    tableName = "search_candidate_cache",
    primaryKeys = ["normalizedQuery", "resultRank"],
    indices = [Index(value = ["normalizedQuery"])]
)
data class SearchCandidateCacheEntity(
    val normalizedQuery: String,
    val resultRank: Int,
    val provider: String,
    val providerSourceId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** 0L = unknown duration. Never negative. */
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val canonicalWatchUrl: String,
    val cachedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Result of a joined query across playlist_entries + tracks + track_sources (selected).
 * selectedSourceArtworkUrl is the artwork from the matched YouTube candidate's track_source row.
 */
data class PlaylistEntryWithTrack(
    val entryId: Long,
    val playlistId: String,
    val position: Int,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** 0L = unknown duration (e.g. Spotify embed did not provide one). */
    val durationMs: Long,
    val artworkUri: String?,
    val artworkUrl: String? = null,
    val spotifyId: String?,
    val resolutionState: ResolutionState,
    val downloadState: DownloadState,
    val localContentUri: String?,
    val dateAddedEpochMs: Long,
    /** Artwork from the selected track_source (YouTube match). Null until track is matched. */
    val selectedSourceArtworkUrl: String? = null,
    val artworkOrigin: String = ArtworkOrigin.UNKNOWN,
    val artworkEnrichmentStatus: String = ArtworkEnrichmentStatus.PENDING
)

/**
 * Represents actual tapped or played media items from search results.
 * Distinct from plain text query history.
 * Bounded to 15 items by SearchHistoryDao; survives app restarts; never contains signed URLs.
 */
@Entity(
    tableName = "recent_search_items",
    indices = [Index(value = ["lastInteractedAtEpochMs"])]
)
data class RecentSearchItemEntity(
    @PrimaryKey
    val id: String,
    val provider: String,
    val providerSourceId: String,
    val catalogTrackId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val itemType: String = "TRACK",
    val lastInteractedAtEpochMs: Long = System.currentTimeMillis(),
    val sourceContext: String = "SEARCH"
) {
    companion object {
        fun recentItemId(provider: String, sourceId: String): String =
            "${provider.trim().lowercase()}:${sourceId.trim()}"

        fun create(
            provider: String,
            providerSourceId: String,
            title: String,
            artist: String,
            album: String? = null,
            durationMs: Long? = null,
            artworkUrl: String? = null,
            catalogTrackId: String? = null,
            itemType: String = "TRACK",
            sourceContext: String = "SEARCH",
            lastInteractedAtEpochMs: Long = System.currentTimeMillis()
        ): RecentSearchItemEntity = RecentSearchItemEntity(
            id = recentItemId(provider, providerSourceId),
            provider = provider,
            providerSourceId = providerSourceId,
            catalogTrackId = catalogTrackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            itemType = itemType,
            lastInteractedAtEpochMs = lastInteractedAtEpochMs,
            sourceContext = sourceContext
        )
    }
}

data class PlaylistSummary(
    val playlistId: String,
    val title: String,
    val sourceUrl: String?,
    val artworkUri: String?,
    val trackCount: Int,
    val firstTrackArtworkUrl: String?,
    val dateCreatedEpochMs: Long,
    val dateModifiedEpochMs: Long
)

data class PlaylistTrackWithPlaylist(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val artworkUrl: String?,
    val localContentUri: String?,
    val playlistId: String,
    val playlistTitle: String
)

data class YouTubePlaylistTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val position: Int
)
