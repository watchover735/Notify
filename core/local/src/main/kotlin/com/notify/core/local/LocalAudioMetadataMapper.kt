package com.notify.core.local

import android.content.ContentUris
import android.net.Uri
import com.notify.core.model.AudioSource
import com.notify.core.model.Track
import com.notify.core.model.TrackId

/**
 * Raw data extracted from a single MediaStore audio row.
 * Decoupled from Android's [android.database.Cursor] for 100% pure JVM testability.
 */
data class MediaStoreAudioRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val dateModifiedEpochSeconds: Long?
)

/**
 * Pure metadata mapper transforming raw MediaStore rows and SAF document queries into domain entities.
 */
object LocalAudioMetadataMapper {

    const val UNKNOWN_TITLE = "Unknown Title"
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"

    /**
     * Maps a pure [MediaStoreAudioRow] and completed item URI string to [LocalAudioItem].
     * The [itemContentUriString] is the final, completed content URI (e.g., content://media/external/audio/media/1000049464).
     * It is stored directly into TrackId and AudioSource.Local without appending the ID again.
     */
    fun fromRow(
        row: MediaStoreAudioRow,
        itemContentUriString: String
    ): LocalAudioItem {
        val title = row.title?.trim()?.ifEmpty { null } ?: UNKNOWN_TITLE
        val artist = row.artist?.trim()?.ifEmpty { null } ?: UNKNOWN_ARTIST
        val album = row.album?.trim()?.ifEmpty { null } ?: UNKNOWN_ALBUM
        val durationMs = maxOf(0L, row.durationMs ?: 0L)
        val sizeBytes = maxOf(0L, row.sizeBytes ?: 0L)
        val dateModifiedEpochSeconds = maxOf(0L, row.dateModifiedEpochSeconds ?: 0L)

        val track = Track(
            id = TrackId.local(itemContentUriString),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUri = null, // In Phase 2/3, legacy album art URIs are forbidden; loadThumbnail deferred to UI phase
            source = AudioSource.Local(contentUriString = itemContentUriString),
            isExplicit = false
        )

        return LocalAudioItem(
            track = track,
            sizeBytes = sizeBytes,
            mimeType = row.mimeType,
            dateModifiedEpochSeconds = dateModifiedEpochSeconds
        )
    }

    /**
     * Overload taking [Uri] for Android runtime invocation.
     */
    fun fromRow(
        row: MediaStoreAudioRow,
        itemUri: Uri
    ): LocalAudioItem {
        return fromRow(row, itemUri.toString())
    }

    /**
     * Maps SAF document metadata to [LocalAudioItem].
     */
    fun fromSafDocument(
        contentUriString: String,
        displayName: String?,
        sizeBytes: Long?,
        mimeType: String?,
        durationMs: Long? = 0L,
        artist: String? = null,
        album: String? = null,
        dateModifiedEpochSeconds: Long? = null
    ): LocalAudioItem {
        // Strip common audio file extensions for a cleaner title if display name is a filename
        val rawTitle = displayName?.substringBeforeLast('.')?.trim()?.ifEmpty { null }
        val title = rawTitle ?: UNKNOWN_TITLE
        val safeArtist = artist?.trim()?.ifEmpty { null } ?: UNKNOWN_ARTIST
        val safeAlbum = album?.trim()?.ifEmpty { null } ?: UNKNOWN_ALBUM

        val track = Track(
            id = TrackId.local(contentUriString),
            title = title,
            artist = safeArtist,
            album = safeAlbum,
            durationMs = maxOf(0L, durationMs ?: 0L),
            artworkUri = null,
            source = AudioSource.Local(contentUriString),
            isExplicit = false
        )

        return LocalAudioItem(
            track = track,
            sizeBytes = maxOf(0L, sizeBytes ?: 0L),
            mimeType = mimeType,
            dateModifiedEpochSeconds = maxOf(0L, dateModifiedEpochSeconds ?: 0L)
        )
    }
}
