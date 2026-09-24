package com.notify.core.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.notify.core.model.PlaybackError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Scans local device audio via [MediaStore.Audio.Media] with coroutine cancellation support
 * and API-specific collection URI resolution.
 */
class MediaStoreAudioScanner(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    /**
     * Resolves the proper MediaStore audio collection URI for the current API level.
     * Uses VOLUME_EXTERNAL on API 29+ and EXTERNAL_CONTENT_URI on API < 29.
     */
    val audioCollectionUri: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * Scans MediaStore for audio tracks.
     * Executes entirely on [ioDispatcher] and checks coroutine cancellation during row iteration.
     */
    suspend fun scan(): LocalAudioResult<List<LocalAudioItem>> = withContext(ioDispatcher) {
        if (!LocalAudioPermissionHelper.hasPermission(context)) {
            return@withContext LocalAudioResult.Failure(
                PlaybackError.PermissionRevoked(
                    permission = LocalAudioPermissionHelper.currentRequiredPermission,
                    message = "Audio permission required to scan device library."
                )
            )
        }

        val collectionUri = audioCollectionUri
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val items = mutableListOf<LocalAudioItem>()

        try {
            contentResolver.query(
                collectionUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()

                    val durationMs = if (durationCol != -1 && !cursor.isNull(durationCol)) cursor.getLong(durationCol) else null
                    // Filter or skip entries with non-positive duration
                    if (durationMs == null || durationMs <= 0L) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val title = if (titleCol != -1 && !cursor.isNull(titleCol)) cursor.getString(titleCol) else null
                    val artist = if (artistCol != -1 && !cursor.isNull(artistCol)) cursor.getString(artistCol) else null
                    val album = if (albumCol != -1 && !cursor.isNull(albumCol)) cursor.getString(albumCol) else null
                    val mimeType = if (mimeCol != -1 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else null
                    val sizeBytes = if (sizeCol != -1 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null
                    val dateModifiedEpochSeconds = if (dateCol != -1 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else null

                    val row = MediaStoreAudioRow(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        dateModifiedEpochSeconds = dateModifiedEpochSeconds
                    )

                    val itemUri = ContentUris.withAppendedId(collectionUri, id)
                    val item = LocalAudioMetadataMapper.fromRow(row, itemUri)
                    items.add(item)
                }
            }
            LocalAudioResult.Success(items)
        } catch (e: SecurityException) {
            LocalAudioResult.Failure(
                PlaybackError.PermissionRevoked(
                    permission = LocalAudioPermissionHelper.currentRequiredPermission,
                    message = "Permission revoked while querying MediaStore: ${e.message}"
                )
            )
        } catch (e: Exception) {
            LocalAudioResult.Failure(
                PlaybackError.Unknown(
                    message = "Unexpected error while scanning MediaStore: ${e.message}",
                    cause = e
                )
            )
        }
    }
}
