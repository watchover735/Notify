package com.notify.core.downloads.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Status progression for persistent Room-backed download queue items.
 */
object DownloadQueueStatus {
    const val QUEUED = "QUEUED"
    const val RESOLVING = "RESOLVING"
    const val DOWNLOADING = "DOWNLOADING"
    const val VALIDATING = "VALIDATING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"
}

/**
 * Represents a queued track download.
 *
 * INVARIANT: trackId is the PRIMARY KEY, enforcing global deduplication across
 * all playlists and single-track requests. A track present in multiple playlists
 * shares exactly one download queue entry and one physical offline audio file.
 */
@Entity(
    tableName = "download_queue",
    indices = [
        Index(value = ["status"]),
        Index(value = ["queuePosition"]),
        Index(value = ["playlistId"])
    ]
)
data class DownloadQueueEntity(
    /** Stable catalog trackId (e.g. Spotify ID or internal ID). Primary Key for global deduplication. */
    @PrimaryKey
    val trackId: String,

    /** Current status in the queue. See [DownloadQueueStatus]. */
    val status: String = DownloadQueueStatus.QUEUED,

    /** Download progress percentage (0 - 100). */
    val progressPercent: Int = 0,

    /** Human-readable title for UI queue status banner ("Current: Song title"). */
    val trackTitle: String = "",

    /** Track artist for display and matching. */
    val artist: String = "",

    /** Track album name if known. */
    val album: String? = null,

    /** Track duration in milliseconds if known. */
    val durationMs: Long = 0L,

    /** Optional artwork URL for UI display. */
    val artworkUrl: String? = null,

    /** The playlist from which this track was enqueued (optional, for grouping). */
    val playlistId: String? = null,

    /** Track position in the source playlist (1-indexed). */
    val playlistPosition: Int = 0,

    /** Monotonically increasing queue order index for strict serial progression. */
    val queuePosition: Int = 0,

    /** Number of retry attempts made for this download. */
    val attemptCount: Int = 0,

    /** Error message if download failed. */
    val errorMessage: String? = null,

    /** Unique worker instance ID claiming this item during processing (prevents concurrent execution). */
    val claimedByWorkerId: String? = null,

    /** Epoch ms of last recorded download progress byte/percent update (for stall detection). */
    val lastProgressEpochMs: Long = 0L,

    /** Epoch ms when this entry was first queued. */
    val createdAtEpochMs: Long = System.currentTimeMillis(),

    /** Epoch ms when this entry was last updated. */
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
