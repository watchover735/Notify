package com.notify.download.ingest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.spike.YtDlpFeasibilitySpike
import com.notify.download.spotify.SpotifyTrackMetadata
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TrackIngestor:
 * Ingests a completed audio scratch file into local storage (MediaStore or FileProvider):
 * 1. Sets metadata (title, artist, album, duration).
 * 2. Streams bytes into final permanent location.
 * 3. Removes scratch file ONLY after successful insertion.
 * 4. Produces a domain Track with a permanent content:// URI ready for offline playback.
 */
class TrackIngestor(private val context: Context) {

    private companion object {
        private const val TAG = "TrackIngestor"
    }

    suspend fun ingestTrack(
        downloadedAudio: YtDlpFeasibilitySpike.DownloadedAudio,
        spotifyTrack: SpotifyTrackMetadata
    ): Result<Track> = withContext(Dispatchers.IO) {
        try {
            val scratchFile = downloadedAudio.file
            if (!scratchFile.exists() || scratchFile.length() <= 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Scratch file ${scratchFile.name} does not exist or is empty")
                )
            }

            val sanitizedTitle = spotifyTrack.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val sanitizedArtist = spotifyTrack.primaryArtist.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val extension = downloadedAudio.formatExtension.ifBlank { "m4a" }
            val displayName = "$sanitizedArtist - $sanitizedTitle.$extension"

            val mimeType = when (extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "opus" -> "audio/ogg"
                "wav" -> "audio/wav"
                else -> "audio/mp4"
            }

            val finalUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ingestMediaStoreApi29(scratchFile, displayName, mimeType, spotifyTrack, downloadedAudio.durationMs)
            } else {
                ingestLegacyFileProvider(scratchFile, displayName)
            } ?: return@withContext Result.failure(
                IllegalStateException("Failed to insert audio file into storage")
            )

            // Verify content resolver can open the newly ingested URI
            context.contentResolver.openInputStream(finalUri)?.use { stream ->
                if (stream.available() < 0) {
                    throw IllegalStateException("Ingested URI stream is empty")
                }
            }

            // Cleanup scratch file strictly after verified ingestion
            try {
                scratchFile.delete()
                Log.d(TAG, "Scratch file cleaned up: ${scratchFile.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete scratch file: ${scratchFile.name}", e)
            }

            val track = Track(
                id = TrackId(ProviderId.LOCAL, finalUri.toString()),
                title = spotifyTrack.title,
                artist = spotifyTrack.primaryArtist,
                album = spotifyTrack.album,
                durationMs = downloadedAudio.durationMs,
                artworkUri = spotifyTrack.artworkUrl,
                source = AudioSource.Local(finalUri.toString())
            )

            Log.d(TAG, "Successfully ingested track: ${track.title} -> $finalUri")
            Result.success(track)
        } catch (e: Exception) {
            Log.e(TAG, "Ingestion failed for track: ${spotifyTrack.title}", e)
            Result.failure(e)
        }
    }

    private fun ingestMediaStoreApi29(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        spotifyTrack: SpotifyTrackMetadata,
        durationMs: Long
    ): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, spotifyTrack.title)
            put(MediaStore.Audio.Media.ARTIST, spotifyTrack.primaryArtist)
            put(MediaStore.Audio.Media.ALBUM, spotifyTrack.album ?: "Unknown Album")
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collectionUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val itemUri = resolver.insert(collectionUri, contentValues) ?: return null

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)

            return itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    private fun ingestLegacyFileProvider(sourceFile: File, displayName: String): Uri? {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val targetFile = File(musicDir, displayName)

        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetFile).use { out ->
                input.copyTo(out)
            }
        }

        return FileProvider.getUriForFile(context, "com.notify.fileprovider", targetFile)
    }
}
