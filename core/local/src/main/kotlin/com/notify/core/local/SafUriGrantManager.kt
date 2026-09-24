package com.notify.core.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.notify.core.model.PlaybackError

/**
 * Manages Storage Access Framework (SAF) URI grants, persistence across app restarts,
 * and metadata extraction with API 26+ MediaMetadataRetriever safety.
 */
class SafUriGrantManager(
    private val context: Context,
    private val availabilityChecker: LocalAudioAvailabilityChecker
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    /**
     * Persists read permissions for the selected SAF content URI.
     */
    fun takePersistablePermission(uri: Uri): LocalAudioResult<Unit> {
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            LocalAudioResult.Success(Unit)
        } catch (e: SecurityException) {
            LocalAudioResult.Failure(
                PlaybackError.PermissionRevoked(
                    permission = "SAF_READ_PERMISSION",
                    message = "Failed to take persistable read grant for $uri: ${e.message}"
                )
            )
        } catch (e: Exception) {
            LocalAudioResult.Failure(
                PlaybackError.Unknown(
                    message = "Unexpected failure taking persistable URI permission: ${e.message}",
                    cause = e
                )
            )
        }
    }

    /**
     * Releases a previously persisted read permission.
     */
    fun releasePersistedPermission(uri: Uri): Boolean {
        return try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rebuilds imported [LocalAudioItem] entries from existing persisted read grants on startup.
     * Revalidates each URI so moved or deleted files do not crash the app.
     */
    fun listPersistedAudio(): List<LocalAudioItem> {
        val persistedList = try {
            contentResolver.persistedUriPermissions
        } catch (_: Exception) {
            return emptyList()
        }

        val results = mutableListOf<LocalAudioItem>()
        for (permission in persistedList) {
            if (!permission.isReadPermission) continue
            val uri = permission.uri

            // Revalidate URI accessibility
            when (availabilityChecker.checkAvailability(uri)) {
                is LocalAudioResult.Success -> {
                    val item = extractItemFromUri(uri)
                    results.add(item)
                }
                is LocalAudioResult.Failure -> {
                    // Stale or inaccessible URI; omit from active library without crashing
                }
            }
        }
        return results
    }

    /**
     * Extracts metadata for a single SAF URI, combining OpenableColumns with
     * MediaMetadataRetriever (safely managed via release() in a finally block for API 26 compatibility).
     */
    fun extractItemFromUri(uri: Uri): LocalAudioItem {
        var displayName: String? = null
        var sizeBytes: Long? = null
        val mimeType = contentResolver.getType(uri)

        // Query OpenableColumns for display name and size
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            // Non-fatal; continue to fallback metadata
        }

        var durationMs: Long? = null
        var artist: String? = null
        var album: String? = null

        // Safe MediaMetadataRetriever extraction compatible with minSdk 26
        // Note: Do NOT use close() or use{} as close() requires API 29+
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durString?.toLongOrNull()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        } catch (_: Exception) {
            // Metadata extraction failure returns safe display fallbacks rather than rejecting the file
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // Ignore release exceptions across Android versions
            }
        }

        return LocalAudioMetadataMapper.fromSafDocument(
            contentUriString = uri.toString(),
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            durationMs = durationMs,
            artist = artist,
            album = album
        )
    }
}
