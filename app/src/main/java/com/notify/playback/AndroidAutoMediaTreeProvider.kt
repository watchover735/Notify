package com.notify.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.notify.core.model.AudioSource
import com.notify.core.model.PlaybackOrigin
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.ArtworkResolution
import com.notify.core.playback.LocalArtworkStore
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.engine.OfflineDownloadManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Auto / MediaBrowser content tree provider for NotiFy.
 *
 * Implements standard browsable and playable tree structure:
 * - Root: "NotiFy" (MEDIA_TYPE_FOLDER_MIXED)
 *   - "Liked Songs" (node_liked_songs)
 *   - "Downloads" (node_downloads)
 *   - "Playlists" (node_playlists)
 *     - Custom/Imported Playlists (playlist_{playlistId})
 *
 * Browsing into any playlist or downloads shows real tracks with Title, Artist, Album,
 * and high-res artwork (or local cached file URI for offline songs).
 */
class AndroidAutoMediaTreeProvider(
    private val context: Context,
    private val database: NotiFyDatabase = NotiFyDatabase.getInstance(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "AndroidAutoMediaTree"

        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_LIKED_SONGS = "node_liked_songs"
        const val MEDIA_ID_DOWNLOADS = "node_downloads"
        const val MEDIA_ID_PLAYLISTS = "node_playlists"

        const val PREFIX_PLAYLIST = "playlist_"
        const val PREFIX_TRACK = "track_"
        const val PREFIX_DOWNLOAD = "download_"
    }

    private val playlistDao = database.playlistDao()
    private val offlineDownloadDao = database.offlineDownloadDao()
    private val downloadManager = OfflineDownloadManager(context)

    /**
     * Checks if [mediaId] originated from this content tree.
     */
    fun isContentTreeMediaId(mediaId: String?): Boolean {
        if (mediaId.isNullOrBlank()) return false
        return mediaId == MEDIA_ID_ROOT ||
                mediaId == MEDIA_ID_LIKED_SONGS ||
                mediaId == MEDIA_ID_DOWNLOADS ||
                mediaId == MEDIA_ID_PLAYLISTS ||
                mediaId.startsWith(PREFIX_PLAYLIST) ||
                mediaId.startsWith(PREFIX_TRACK) ||
                mediaId.startsWith(PREFIX_DOWNLOAD)
    }

    /**
     * Builds root MediaItem for Android Auto.
     */
    fun getRoot(params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = MediaItem.Builder()
            .setMediaId(MEDIA_ID_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("NotiFy")
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    /**
     * Asynchronously loads children for a given [parentId] in the content tree.
     */
    fun getChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
        scope: CoroutineScope
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

        scope.launch(ioDispatcher) {
            try {
                val items = when (parentId) {
                    MEDIA_ID_ROOT -> getRootChildren()
                    MEDIA_ID_PLAYLISTS -> getPlaylistsChildren()
                    MEDIA_ID_LIKED_SONGS -> getPlaylistTracksChildren(PlaylistRepository.LIKED_SONGS_PLAYLIST_ID)
                    MEDIA_ID_DOWNLOADS -> getDownloadsChildren()
                    else -> {
                        if (parentId.startsWith(PREFIX_PLAYLIST)) {
                            val playlistId = parentId.removePrefix(PREFIX_PLAYLIST)
                            getPlaylistTracksChildren(playlistId)
                        } else {
                            emptyList()
                        }
                    }
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving children for $parentId: ${e.message}", e)
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }

        return future
    }

    /**
     * Retrieves an individual item by its [mediaId].
     */
    fun getItem(
        mediaId: String,
        scope: CoroutineScope
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val future = SettableFuture.create<LibraryResult<MediaItem>>()

        scope.launch(ioDispatcher) {
            try {
                val item = when {
                    mediaId == MEDIA_ID_ROOT -> {
                        MediaItem.Builder()
                            .setMediaId(MEDIA_ID_ROOT)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("NotiFy")
                                    .setIsPlayable(false)
                                    .setIsBrowsable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .build()
                            ).build()
                    }
                    mediaId == MEDIA_ID_LIKED_SONGS -> buildLikedSongsFolderItem()
                    mediaId == MEDIA_ID_DOWNLOADS -> buildDownloadsFolderItem()
                    mediaId == MEDIA_ID_PLAYLISTS -> buildPlaylistsFolderItem()
                    mediaId.startsWith(PREFIX_DOWNLOAD) -> {
                        val trackId = mediaId.removePrefix(PREFIX_DOWNLOAD)
                        val dl = offlineDownloadDao.getCompletedForTrack(trackId)
                            ?: offlineDownloadDao.getById(trackId)
                            ?: offlineDownloadDao.getCompletedForSource(trackId)
                        dl?.let { buildDownloadTrackItem(it) }
                    }
                    mediaId.startsWith(PREFIX_TRACK) -> {
                        // format: track_{playlistId}::{trackId}::{position} or legacy track_{playlistId}_{trackId}_{position}
                        val content = mediaId.removePrefix(PREFIX_TRACK)
                        val trackId = if (content.contains("::")) {
                            val parts = content.split("::")
                            parts.getOrNull(1) ?: parts[0]
                        } else {
                            val lastUnderscore = content.lastIndexOf('_')
                            val secondLastUnderscore = if (lastUnderscore > 0) content.lastIndexOf('_', lastUnderscore - 1) else -1
                            if (secondLastUnderscore > 0) {
                                content.substring(secondLastUnderscore + 1, lastUnderscore)
                            } else {
                                val parts = content.split("_")
                                parts.getOrNull(parts.size - 2) ?: content
                            }
                        }
                        val track = database.trackDao().getTrackById(trackId)
                        track?.let { t ->
                            val localArt = LocalArtworkStore.getArtworkUri(t.id, context)
                            val art = localArt?.toString() ?: t.artworkUrl ?: t.artworkUri
                            val highRes = art?.let { ArtworkResolution.highResArtwork(it, targetPx = 800) }
                            MediaItem.Builder()
                                .setMediaId(mediaId)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(t.title)
                                        .setArtist(t.artist)
                                        .setAlbumTitle(t.album)
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .setArtworkUri(highRes?.let { Uri.parse(it) })
                                        .build()
                                ).build()
                        }
                    }
                    mediaId.startsWith(PREFIX_PLAYLIST) -> {
                        val playlistId = mediaId.removePrefix(PREFIX_PLAYLIST)
                        val pl = playlistDao.getPlaylistById(playlistId)
                        pl?.let { buildPlaylistItem(it) }
                    }
                    else -> null
                }

                if (item != null) {
                    future.set(LibraryResult.ofItem(item, null))
                } else {
                    future.set(LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in getItem for $mediaId: ${e.message}", e)
                future.set(LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        return future
    }

    /**
     * Top-level nodes under Root: Liked Songs, Downloads, Playlists.
     */
    private fun getRootChildren(): List<MediaItem> {
        return listOf(
            buildLikedSongsFolderItem(),
            buildDownloadsFolderItem(),
            buildPlaylistsFolderItem()
        )
    }

    private fun buildLikedSongsFolderItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_LIKED_SONGS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Liked Songs")
                    .setIsPlayable(true)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build()
            )
            .build()
    }

    private fun buildDownloadsFolderItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_DOWNLOADS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Downloads")
                    .setIsPlayable(true)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    private fun buildPlaylistsFolderItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_PLAYLISTS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Playlists")
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                    .build()
            )
            .build()
    }

    /**
     * Playlists category children: all custom/imported playlists.
     */
    private suspend fun getPlaylistsChildren(): List<MediaItem> {
        val allPlaylists = playlistDao.getAllPlaylists()
        return allPlaylists
            .filter { it.playlistId != PlaylistRepository.LIKED_SONGS_PLAYLIST_ID }
            .map { buildPlaylistItem(it) }
    }

    private suspend fun buildPlaylistItem(playlist: PlaylistEntity): MediaItem {
        val entries = playlistDao.getPlaylistEntries(playlist.playlistId)
        val firstArt = entries.firstOrNull { !it.artworkUrl.isNullOrBlank() || !it.artworkUri.isNullOrBlank() }
        val rawArt = playlist.artworkUri ?: firstArt?.artworkUrl ?: firstArt?.artworkUri
        val highResArt = rawArt?.let { ArtworkResolution.highResArtwork(it, targetPx = 800) }

        return MediaItem.Builder()
            .setMediaId("$PREFIX_PLAYLIST${playlist.playlistId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(playlist.title)
                    .setSubtitle("${entries.size} tracks")
                    .setIsPlayable(true)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setArtworkUri(highResArt?.let { Uri.parse(it) })
                    .build()
            )
            .build()
    }

    /**
     * Playlist tracks children: real tracks inside a playlist.
     */
    private suspend fun getPlaylistTracksChildren(playlistId: String): List<MediaItem> {
        val entries = playlistDao.getPlaylistEntries(playlistId)
        return entries.map { entry ->
            val localArtUri = LocalArtworkStore.getArtworkUri(entry.trackId, context)
            val rawArt = localArtUri?.toString()
                ?: entry.selectedSourceArtworkUrl
                ?: entry.artworkUrl
                ?: entry.artworkUri
            val resolvedArt = rawArt?.let { ArtworkResolution.highResArtwork(it, targetPx = 800) }

            MediaItem.Builder()
                .setMediaId("${PREFIX_TRACK}${playlistId}::${entry.trackId}::${entry.position}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(entry.artist)
                        .setAlbumTitle(entry.album)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setArtworkUri(resolvedArt?.let { Uri.parse(it) })
                        .build()
                )
                .build()
        }
    }

    /**
     * Downloads category children: completed offline downloads.
     */
    private suspend fun getDownloadsChildren(): List<MediaItem> {
        val completed = offlineDownloadDao.getCompletedDownloads()
        return completed.map { dl -> buildDownloadTrackItem(dl) }
    }

    private suspend fun buildDownloadTrackItem(dl: com.notify.core.downloads.db.OfflineDownloadEntity): MediaItem {
        val trackEntity = database.trackDao().getTrackById(dl.trackId)
        val localArtUri = LocalArtworkStore.getArtworkUri(dl.trackId, context)
            ?: LocalArtworkStore.getArtworkUri(dl.providerSourceId, context)
        val rawArt = localArtUri?.toString() ?: trackEntity?.artworkUrl ?: trackEntity?.artworkUri
        val resolvedArt = rawArt?.let { ArtworkResolution.highResArtwork(it, targetPx = 800) }
        val artUri = resolvedArt?.let { Uri.parse(it) }

        return MediaItem.Builder()
            .setMediaId("$PREFIX_DOWNLOAD${dl.trackId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(trackEntity?.title ?: "Track ${dl.providerSourceId}")
                    .setArtist(trackEntity?.artist ?: "Unknown Artist")
                    .setAlbumTitle(trackEntity?.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(artUri)
                    .build()
            )
            .build()
    }

    /**
     * Builds the complete queue of [QueueEntry]s and determines the [startIndex]
     * when a user taps a MediaItem from the content tree in Android Auto.
     */
    suspend fun buildQueueForMediaId(mediaId: String): Pair<List<QueueEntry>, Int> = withContext(ioDispatcher) {
        when {
            // Case 1: Tapped a downloaded track
            mediaId.startsWith(PREFIX_DOWNLOAD) -> {
                val targetTrackId = mediaId.removePrefix(PREFIX_DOWNLOAD)
                val completed = offlineDownloadDao.getCompletedDownloads()
                val entries = completed.mapNotNull { dl ->
                    val contentUri = downloadManager.getOfflinePlaybackUri(dl.trackId)
                        ?: downloadManager.getOfflinePlaybackUriForSource(dl.providerSourceId)
                        ?: return@mapNotNull null
                    val trackEntity = database.trackDao().getTrackById(dl.trackId)
                    val localArt = LocalArtworkStore.getArtworkUri(dl.trackId, context)?.toString()
                        ?: LocalArtworkStore.getArtworkUri(dl.providerSourceId, context)?.toString()
                        ?: trackEntity?.artworkUrl
                        ?: trackEntity?.artworkUri
                    val track = Track(
                        id = TrackId.spotify(dl.trackId),
                        title = trackEntity?.title ?: "Track ${dl.providerSourceId}",
                        artist = trackEntity?.artist ?: "Unknown Artist",
                        album = trackEntity?.album,
                        durationMs = dl.durationMs ?: trackEntity?.durationMs ?: 0L,
                        artworkUri = localArt,
                        source = AudioSource.Local(contentUri.toString())
                    )
                    QueueEntry(
                        queueId = "download_${dl.trackId}",
                        track = track,
                        origin = QueueOrigin.USER,
                        playbackOrigin = PlaybackOrigin.OFFLINE_DOWNLOAD
                    )
                }
                val targetIndex = entries.indexOfFirst { it.track.id.rawId == targetTrackId }.coerceAtLeast(0)
                entries to targetIndex
            }

            // Case 2: Tapped Downloads folder directly
            mediaId == MEDIA_ID_DOWNLOADS -> {
                val completed = offlineDownloadDao.getCompletedDownloads()
                val entries = completed.mapNotNull { dl ->
                    val contentUri = downloadManager.getOfflinePlaybackUri(dl.trackId)
                        ?: downloadManager.getOfflinePlaybackUriForSource(dl.providerSourceId)
                        ?: return@mapNotNull null
                    val trackEntity = database.trackDao().getTrackById(dl.trackId)
                    val localArt = LocalArtworkStore.getArtworkUri(dl.trackId, context)?.toString()
                        ?: LocalArtworkStore.getArtworkUri(dl.providerSourceId, context)?.toString()
                        ?: trackEntity?.artworkUrl
                        ?: trackEntity?.artworkUri
                    val track = Track(
                        id = TrackId.spotify(dl.trackId),
                        title = trackEntity?.title ?: "Track ${dl.providerSourceId}",
                        artist = trackEntity?.artist ?: "Unknown Artist",
                        album = trackEntity?.album,
                        durationMs = dl.durationMs ?: trackEntity?.durationMs ?: 0L,
                        artworkUri = localArt,
                        source = AudioSource.Local(contentUri.toString())
                    )
                    QueueEntry(
                        queueId = "download_${dl.trackId}",
                        track = track,
                        origin = QueueOrigin.USER,
                        playbackOrigin = PlaybackOrigin.OFFLINE_DOWNLOAD
                    )
                }
                entries to 0
            }

            // Case 3: Tapped a track inside a playlist
            // Format: track_{playlistId}::{trackId}::{position} or legacy track_{playlistId}_{trackId}_{position}
            mediaId.startsWith(PREFIX_TRACK) -> {
                val content = mediaId.removePrefix(PREFIX_TRACK)
                val (playlistId, targetTrackId) = if (content.contains("::")) {
                    val parts = content.split("::")
                    val pId = parts.getOrNull(0) ?: PlaylistRepository.LIKED_SONGS_PLAYLIST_ID
                    val tId = parts.getOrNull(1) ?: ""
                    pId to tId
                } else {
                    val lastUnderscore = content.lastIndexOf('_')
                    val secondLastUnderscore = if (lastUnderscore > 0) content.lastIndexOf('_', lastUnderscore - 1) else -1
                    if (secondLastUnderscore > 0) {
                        val pId = content.substring(0, secondLastUnderscore)
                        val tId = content.substring(secondLastUnderscore + 1, lastUnderscore)
                        pId to tId
                    } else {
                        val parts = content.split("_")
                        val pId = parts.getOrNull(0) ?: PlaylistRepository.LIKED_SONGS_PLAYLIST_ID
                        val tId = parts.getOrNull(1) ?: ""
                        pId to tId
                    }
                }

                val entriesWithTrack = playlistDao.getPlaylistEntries(playlistId)
                val queueEntries = entriesWithTrack.map { item ->
                    val localArtUri = LocalArtworkStore.getArtworkUri(item.trackId, context)
                    val effectiveArtwork = localArtUri?.toString()
                        ?: item.selectedSourceArtworkUrl?.takeIf { it.isNotBlank() }
                        ?: item.artworkUrl?.takeIf { it.isNotBlank() }
                        ?: item.artworkUri?.takeIf { it.isNotBlank() }
                    val source: AudioSource = if (!item.localContentUri.isNullOrBlank()) {
                        AudioSource.Local(item.localContentUri)
                    } else {
                        AudioSource.Remote(ProviderId.SPOTIFY, item.trackId)
                    }
                    val track = Track(
                        id = TrackId.spotify(item.trackId),
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs,
                        artworkUri = effectiveArtwork,
                        source = source
                    )
                    QueueEntry(
                        queueId = "playlist_${playlistId}_${item.trackId}_${item.position}",
                        track = track,
                        origin = QueueOrigin.PLAYLIST,
                        playbackOrigin = PlaybackOrigin.PLAYLIST
                    )
                }
                val targetIndex = queueEntries.indexOfFirst { it.track.id.rawId == targetTrackId }.coerceAtLeast(0)
                queueEntries to targetIndex
            }

            // Case 4: Tapped Liked Songs folder directly
            mediaId == MEDIA_ID_LIKED_SONGS -> {
                val entriesWithTrack = playlistDao.getPlaylistEntries(PlaylistRepository.LIKED_SONGS_PLAYLIST_ID)
                val queueEntries = entriesWithTrack.map { item ->
                    val localArtUri = LocalArtworkStore.getArtworkUri(item.trackId, context)
                    val effectiveArtwork = localArtUri?.toString()
                        ?: item.selectedSourceArtworkUrl?.takeIf { it.isNotBlank() }
                        ?: item.artworkUrl?.takeIf { it.isNotBlank() }
                        ?: item.artworkUri?.takeIf { it.isNotBlank() }
                    val source: AudioSource = if (!item.localContentUri.isNullOrBlank()) {
                        AudioSource.Local(item.localContentUri)
                    } else {
                        AudioSource.Remote(ProviderId.SPOTIFY, item.trackId)
                    }
                    val track = Track(
                        id = TrackId.spotify(item.trackId),
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs,
                        artworkUri = effectiveArtwork,
                        source = source
                    )
                    QueueEntry(
                        queueId = "playlist_${PlaylistRepository.LIKED_SONGS_PLAYLIST_ID}_${item.trackId}_${item.position}",
                        track = track,
                        origin = QueueOrigin.PLAYLIST,
                        playbackOrigin = PlaybackOrigin.PLAYLIST
                    )
                }
                queueEntries to 0
            }

            // Case 5: Tapped a playlist card directly
            mediaId.startsWith(PREFIX_PLAYLIST) -> {
                val playlistId = mediaId.removePrefix(PREFIX_PLAYLIST)
                val entriesWithTrack = playlistDao.getPlaylistEntries(playlistId)
                val queueEntries = entriesWithTrack.map { item ->
                    val localArtUri = LocalArtworkStore.getArtworkUri(item.trackId, context)
                    val effectiveArtwork = localArtUri?.toString()
                        ?: item.selectedSourceArtworkUrl?.takeIf { it.isNotBlank() }
                        ?: item.artworkUrl?.takeIf { it.isNotBlank() }
                        ?: item.artworkUri?.takeIf { it.isNotBlank() }
                    val source: AudioSource = if (!item.localContentUri.isNullOrBlank()) {
                        AudioSource.Local(item.localContentUri)
                    } else {
                        AudioSource.Remote(ProviderId.SPOTIFY, item.trackId)
                    }
                    val track = Track(
                        id = TrackId.spotify(item.trackId),
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs,
                        artworkUri = effectiveArtwork,
                        source = source
                    )
                    QueueEntry(
                        queueId = "playlist_${playlistId}_${item.trackId}_${item.position}",
                        track = track,
                        origin = QueueOrigin.PLAYLIST,
                        playbackOrigin = PlaybackOrigin.PLAYLIST
                    )
                }
                queueEntries to 0
            }

            else -> emptyList<QueueEntry>() to 0
        }
    }
}
