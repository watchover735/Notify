package com.notify.core.downloads.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Status progression for offline downloads.
 */
object OfflineDownloadStatus {
    const val PENDING = "PENDING"
    const val RESOLVING = "RESOLVING"
    const val DOWNLOADING = "DOWNLOADING"
    const val VALIDATING = "VALIDATING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val EVICTED = "EVICTED"
}

/**
 * Persists a NotiFy offline download, one row per physical audio file.
 * One file can be shared across multiple playlists via the shared trackId.
 *
 * INVARIANT: relativeStorageKey is the ONLY path persisted — never absolute.
 * INVARIANT: No signed stream URLs are stored in this entity.
 */
@Entity(
    tableName = "offline_downloads",
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["relativeStorageKey"], unique = true),
        Index(value = ["status"]),
        Index(value = ["bucket", "lastAccessedAtEpochMs"])
    ]
)
data class OfflineDownloadEntity(
    /** Unique download ID (UUID). */
    @PrimaryKey
    val downloadId: String,

    /** References TrackEntity.id from the main database. */
    val trackId: String,

    /** Provider identifier (e.g. "YOUTUBE"). */
    val provider: String,

    /** Provider-specific source ID (e.g. YouTube video ID). */
    val providerSourceId: String,

    /** Canonical URL used for yt-dlp extraction (e.g. https://www.youtube.com/watch?v=...). */
    val canonicalUrl: String,

    /** Relative path within app-managed storage. Never an absolute path. */
    val relativeStorageKey: String,

    /** Storage bucket: "PINNED" or "SMART_OFFLINE". */
    val bucket: String,

    /** Current download status (see OfflineDownloadStatus). */
    val status: String = OfflineDownloadStatus.PENDING,

    /** Audio file size in bytes (set after download completes). */
    val fileSizeBytes: Long? = null,

    /** Audio file MIME type (e.g. "audio/mp4", "audio/webm"). */
    val mimeType: String? = null,

    /** Audio bitrate in kbps (for display/sorting). */
    val bitrateKbps: Int? = null,

    /** Duration in milliseconds (from yt-dlp metadata). */
    val durationMs: Long? = null,

    /** Audio quality profile used for this download. */
    val qualityProfile: String = "NORMAL",

    /** Download progress 0-100. */
    val progressPercent: Int = 0,

    /** Human-readable error message on failure. */
    val errorMessage: String? = null,

    /** Number of download attempts. */
    val attemptCount: Int = 0,

    /** Epoch ms when this download was first created. */
    val createdAtEpochMs: Long = System.currentTimeMillis(),

    /** Epoch ms when the status was last updated. */
    val updatedAtEpochMs: Long = System.currentTimeMillis(),

    /** Epoch ms when this file was last accessed for playback (for LRU). */
    val lastAccessedAtEpochMs: Long = System.currentTimeMillis()
)
