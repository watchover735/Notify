package com.notify.sync

import android.content.Context
import android.util.Log
import com.notify.auth.SupabaseAuthRepository
import com.notify.download.db.ArtworkOrigin
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.stream.SupabaseConfig
import com.notify.download.worker.ArtworkEnrichmentWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles cloud backup and cross-device sync of playlists linked to the user's Supabase auth email/account.
 *
 * Requirements:
 * - NO MP3 or audio files are stored in the cloud.
 * - Stores only metadata JSON: spotify_id, youtube_id, title, artist, duration, artwork_url, position.
 * - On login or new device, automatically restores playlists and track lists into local Room DB.
 */
class CloudPlaylistSyncRepository(
    private val context: Context,
    private val database: NotiFyDatabase = NotiFyDatabase.getInstance(context),
    private val authRepo: SupabaseAuthRepository = SupabaseAuthRepository(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "CloudPlaylistSync"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: CloudPlaylistSyncRepository? = null

        fun getInstance(context: Context): CloudPlaylistSyncRepository {
            return instance ?: synchronized(this) {
                instance ?: CloudPlaylistSyncRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val playlistDao = database.playlistDao()
    private val trackDao = database.trackDao()

    /**
     * Uploads/upserts a local playlist and its track metadata to the user's cloud account.
     */
    suspend fun syncPlaylistToCloud(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        val session = authRepo.getStoredSession()
        if (session == null || session.accessToken.isBlank()) {
            Log.d(TAG, "Skipping cloud sync: user not authenticated")
            return@withContext false
        }

        val playlist = playlistDao.getPlaylistById(playlistId) ?: return@withContext false
        val entriesWithTrack = playlistDao.getPlaylistEntries(playlistId)

        val tracksArray = JSONArray()
        for (item in entriesWithTrack) {
            val trackObj = JSONObject().apply {
                put("track_id", item.trackId)
                if (!item.spotifyId.isNullOrBlank()) {
                    put("spotify_id", item.spotifyId)
                }
                if (item.trackId.startsWith("youtube:")) {
                    put("youtube_id", item.trackId.removePrefix("youtube:"))
                }
                put("title", item.title)
                put("artist", item.artist)
                item.album?.let { put("album", it) }
                put("duration", (item.durationMs / 1000L).toInt())
                put("duration_ms", item.durationMs)
                val art = item.artworkUrl ?: item.artworkUri ?: item.selectedSourceArtworkUrl
                if (!art.isNullOrBlank()) {
                    put("artwork_url", art)
                }
                put("position", item.position)
            }
            tracksArray.put(trackObj)
        }

        val payload = JSONObject().apply {
            put("user_id", session.userId)
            put("playlist_id", playlist.playlistId)
            put("title", playlist.title)
            playlist.sourceUrl?.let { put("source_url", it) }
            playlist.artworkUri?.let { put("artwork_uri", it) }
            put("tracks_json", tracksArray)
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.REST_URL}/user_playlists?on_conflict=user_id,playlist_id")
            .header("apikey", SupabaseConfig.ANON_KEY)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully synced playlist '${playlist.title}' (${tracksArray.length()} tracks) to cloud")
                    true
                } else {
                    Log.w(TAG, "Failed to sync playlist to cloud: code=${response.code} body=${response.body?.string()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud sync network error: ${e.message}")
            false
        }
    }

    /**
     * Deletes a playlist from the user's cloud account.
     */
    suspend fun deletePlaylistFromCloud(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        val session = authRepo.getStoredSession() ?: return@withContext false
        val request = Request.Builder()
            .url("${SupabaseConfig.REST_URL}/user_playlists?playlist_id=eq.$playlistId")
            .header("apikey", SupabaseConfig.ANON_KEY)
            .header("Authorization", "Bearer ${session.accessToken}")
            .delete()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete cloud playlist: ${e.message}")
            false
        }
    }

    /**
     * Fetches cloud playlists for the current authenticated user and restores any missing playlists
     * into the local Room database without requiring the user to re-import them.
     */
    suspend fun syncDownPlaylists(): Int = withContext(Dispatchers.IO) {
        val session = authRepo.getStoredSession()
        if (session == null || session.accessToken.isBlank()) {
            return@withContext 0
        }

        val request = Request.Builder()
            .url("${SupabaseConfig.REST_URL}/user_playlists?select=*&order=updated_at.desc")
            .header("apikey", SupabaseConfig.ANON_KEY)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()

        val jsonString = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch user playlists: ${response.code}")
                    return@withContext 0
                }
                response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching user playlists: ${e.message}")
            return@withContext 0
        }

        if (jsonString.isBlank()) return@withContext 0

        val cloudPlaylists = try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing user playlists JSON: ${e.message}")
            return@withContext 0
        }

        var restoredCount = 0
        val now = System.currentTimeMillis()

        for (i in 0 until cloudPlaylists.length()) {
            val plObj = cloudPlaylists.optJSONObject(i) ?: continue
            val playlistId = plObj.optString("playlist_id")
            if (playlistId.isBlank()) continue

            val existingLocal = playlistDao.getPlaylistById(playlistId)
            val tracksArray = plObj.optJSONArray("tracks_json") ?: JSONArray()

            // If playlist already exists locally and has entries, skip restore
            if (existingLocal != null && playlistDao.getPlaylistEntries(playlistId).isNotEmpty()) {
                continue
            }

            val title = plObj.optString("title", "Imported Playlist")
            val sourceUrl = plObj.optString("source_url").takeIf { it.isNotBlank() }
            val artworkUri = plObj.optString("artwork_uri").takeIf { it.isNotBlank() }

            val playlistEntity = PlaylistEntity(
                playlistId = playlistId,
                title = title,
                sourceUrl = sourceUrl,
                artworkUri = artworkUri ?: existingLocal?.artworkUri,
                dateCreatedEpochMs = existingLocal?.dateCreatedEpochMs ?: now,
                dateModifiedEpochMs = now
            )

            val trackEntities = mutableListOf<TrackEntity>()
            val trackSources = mutableListOf<TrackSourceEntity>()
            val entryEntities = mutableListOf<PlaylistEntryEntity>()
            val seenTrackIds = mutableSetOf<String>()

            for (t in 0 until tracksArray.length()) {
                val tObj = tracksArray.optJSONObject(t) ?: continue
                val trackTitle = tObj.optString("title")
                val trackArtist = tObj.optString("artist")
                val position = tObj.optInt("position", t + 1)
                val durationSec = tObj.optLong("duration", 0L)
                val durationMs = if (tObj.has("duration_ms")) tObj.optLong("duration_ms") else durationSec * 1000L
                val spotifyId = tObj.optString("spotify_id").takeIf { it.isNotBlank() }
                val youtubeId = tObj.optString("youtube_id").takeIf { it.isNotBlank() }
                val artworkUrl = tObj.optString("artwork_url").takeIf { it.isNotBlank() }

                val trackId = tObj.optString("track_id").ifBlank {
                    when {
                        !youtubeId.isNullOrBlank() -> "youtube:$youtubeId"
                        !spotifyId.isNullOrBlank() -> "spotify:${playlistId}_${position}_${Math.abs(trackTitle.hashCode())}"
                        else -> "track_${playlistId}_${position}_${Math.abs(trackTitle.hashCode())}"
                    }
                }

                if (!seenTrackIds.add(trackId)) {
                    entryEntities.add(
                        PlaylistEntryEntity(
                            playlistId = playlistId,
                            trackId = trackId,
                            position = position
                        )
                    )
                    continue
                }

                val isYouTube = trackId.startsWith("youtube:") || !youtubeId.isNullOrBlank()
                val trackEntity = TrackEntity(
                    id = trackId,
                    title = trackTitle,
                    artist = trackArtist,
                    album = tObj.optString("album").takeIf { it.isNotBlank() },
                    durationMs = durationMs,
                    artworkUri = artworkUrl,
                    artworkUrl = artworkUrl,
                    artworkOrigin = if (isYouTube) ArtworkOrigin.YOUTUBE_MATCH else ArtworkOrigin.SPOTIFY_TRACK,
                    spotifyId = spotifyId,
                    resolutionState = if (isYouTube) ResolutionState.MATCHED else ResolutionState.METADATA_ONLY,
                    downloadState = DownloadState.NOT_DOWNLOADED,
                    localContentUri = null,
                    dateAddedEpochMs = now
                )
                trackEntities.add(trackEntity)

                if (isYouTube) {
                    val actualYtId = youtubeId ?: trackId.removePrefix("youtube:")
                    val sourceEntity = TrackSourceEntity(
                        sourceKey = "${trackId}_youtube_${actualYtId}",
                        trackId = trackId,
                        provider = "YOUTUBE",
                        sourceId = actualYtId,
                        canonicalUrl = "https://www.youtube.com/watch?v=$actualYtId",
                        confidence = 1.0f,
                        durationDeltaMs = 0L,
                        artworkUrl = artworkUrl,
                        selected = true
                    )
                    trackSources.add(sourceEntity)
                }

                entryEntities.add(
                    PlaylistEntryEntity(
                        playlistId = playlistId,
                        trackId = trackId,
                        position = position
                    )
                )
            }

            // Persist to Room
            trackDao.upsertTracksSafely(trackEntities)
            if (trackSources.isNotEmpty()) {
                trackDao.insertSources(trackSources)
            }
            playlistDao.insertPlaylist(playlistEntity)
            playlistDao.deletePlaylistEntries(playlistId)
            for (entry in entryEntities) {
                playlistDao.insertPlaylistEntryIgnore(entry)
            }

            // Trigger artwork enrichment if needed
            if (playlistId.startsWith("pl_spotify_")) {
                ArtworkEnrichmentWorker.enqueue(context, playlistId)
            }

            restoredCount++
            Log.d(TAG, "Restored cloud playlist '${title}' with ${entryEntities.size} tracks into local DB")
        }

        restoredCount
    }

    /**
     * Full bidirectional sync:
     * 1. Downloads missing playlists from cloud to Room.
     * 2. Uploads any local playlists that are not yet on cloud.
     */
    suspend fun syncAllPlaylists(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val downloaded = syncDownPlaylists()
        var uploaded = 0

        val localPlaylists = playlistDao.getAllPlaylists()
        for (local in localPlaylists) {
            val entries = playlistDao.getPlaylistEntries(local.playlistId)
            if (entries.isNotEmpty()) {
                if (syncPlaylistToCloud(local.playlistId)) {
                    uploaded++
                }
            }
        }

        Pair(downloaded, uploaded)
    }
}
