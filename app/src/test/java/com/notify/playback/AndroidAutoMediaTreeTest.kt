package com.notify.playback

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.room.Room
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import com.notify.core.model.AudioSource
import com.notify.core.playback.LocalArtworkStore
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AndroidAutoMediaTreeTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var mediaTreeProvider: AndroidAutoMediaTreeProvider
    private val testScope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        LocalArtworkStore.init(context)
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaTreeProvider = AndroidAutoMediaTreeProvider(context, db, Dispatchers.IO)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun isContentTreeMediaId_identifiesTreeMediaIdsCorrectly() {
        assertTrue(mediaTreeProvider.isContentTreeMediaId("root"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("node_liked_songs"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("node_downloads"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("node_playlists"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("playlist_my_playlist_123"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("track_pl1::track_abc_123::0"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("track_pl1_track_abc_123_0"))
        assertTrue(mediaTreeProvider.isContentTreeMediaId("download_spotify_123"))

        assertFalse(mediaTreeProvider.isContentTreeMediaId(null))
        assertFalse(mediaTreeProvider.isContentTreeMediaId(""))
        assertFalse(mediaTreeProvider.isContentTreeMediaId("random_external_id"))
    }

    @Test
    fun getRoot_returnsStandardNotiFyRootNode() {
        val rootResult = mediaTreeProvider.getRoot(null).get(5, TimeUnit.SECONDS)
        assertNotNull(rootResult)
        val rootItem = rootResult.value
        assertNotNull(rootItem)
        assertEquals(AndroidAutoMediaTreeProvider.MEDIA_ID_ROOT, rootItem?.mediaId)
        assertEquals("NotiFy", rootItem?.mediaMetadata?.title?.toString())
        assertEquals(false, rootItem?.mediaMetadata?.isPlayable)
        assertEquals(true, rootItem?.mediaMetadata?.isBrowsable)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, rootItem?.mediaMetadata?.mediaType)
    }

    @Test
    fun getChildren_root_returnsLikedSongsDownloadsAndPlaylistsFolders() {
        val childrenResult = mediaTreeProvider.getChildren(
            parentId = AndroidAutoMediaTreeProvider.MEDIA_ID_ROOT,
            page = 0,
            pageSize = 10,
            params = null,
            scope = testScope
        ).get(5, TimeUnit.SECONDS)

        val items = childrenResult.value ?: emptyList()
        assertEquals(3, items.size)

        val liked = items.find { it.mediaId == AndroidAutoMediaTreeProvider.MEDIA_ID_LIKED_SONGS }
        assertNotNull(liked)
        assertEquals("Liked Songs", liked?.mediaMetadata?.title?.toString())
        assertTrue(liked?.mediaMetadata?.isBrowsable == true)

        val downloads = items.find { it.mediaId == AndroidAutoMediaTreeProvider.MEDIA_ID_DOWNLOADS }
        assertNotNull(downloads)
        assertEquals("Downloads", downloads?.mediaMetadata?.title?.toString())
        assertTrue(downloads?.mediaMetadata?.isBrowsable == true)

        val playlists = items.find { it.mediaId == AndroidAutoMediaTreeProvider.MEDIA_ID_PLAYLISTS }
        assertNotNull(playlists)
        assertEquals("Playlists", playlists?.mediaMetadata?.title?.toString())
        assertTrue(playlists?.mediaMetadata?.isBrowsable == true)
        assertFalse(playlists?.mediaMetadata?.isPlayable == true)
    }

    @Test
    fun getChildren_playlists_returnsCustomPlaylistsWithArtwork() {
        val playlist = PlaylistEntity(
            playlistId = "rock_classics",
            title = "Rock Classics",
            sourceUrl = null,
            artworkUri = "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            dateCreatedEpochMs = 1000L,
            dateModifiedEpochMs = 2000L
        )
        runBlocking { db.playlistDao().insertPlaylist(playlist) }

        val childrenResult = mediaTreeProvider.getChildren(
            parentId = AndroidAutoMediaTreeProvider.MEDIA_ID_PLAYLISTS,
            page = 0,
            pageSize = 10,
            params = null,
            scope = testScope
        ).get(5, TimeUnit.SECONDS)

        val items = childrenResult.value ?: emptyList()
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("playlist_rock_classics", item.mediaId)
        assertEquals("Rock Classics", item.mediaMetadata.title?.toString())
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        // High-res YouTube artwork should be upgraded to maxresdefault.jpg
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            item.mediaMetadata.artworkUri?.toString()
        )
    }

    @Test
    fun getChildren_playlistTracks_returnsPlayableTracksWithSharpMetadata() {
        val playlistId = "indie_vibes"
        runBlocking {
            db.playlistDao().insertPlaylist(
                PlaylistEntity(
                    playlistId = playlistId,
                    title = "Indie Vibes",
                    sourceUrl = null,
                    artworkUri = null,
                    dateCreatedEpochMs = 1000L,
                    dateModifiedEpochMs = 2000L
                )
            )

            val track = TrackEntity(
                id = "track_sub_1",
                title = "Midnight City",
                artist = "M83",
                album = "Hurry Up, We're Dreaming",
                durationMs = 243_000L,
                artworkUri = "https://e-cdns-images.dzcdn.net/images/cover_small/abc.jpg",
                artworkUrl = "https://e-cdns-images.dzcdn.net/images/cover_small/abc.jpg",
                resolutionState = ResolutionState.MATCHED,
                downloadState = DownloadState.NOT_DOWNLOADED
            )
            db.trackDao().insertTrack(track)

            db.playlistDao().insertPlaylistEntries(
                listOf(
                    PlaylistEntryEntity(
                        entryId = 1L,
                        playlistId = playlistId,
                        trackId = "track_sub_1",
                        position = 0
                    )
                )
            )
        }

        val childrenResult = mediaTreeProvider.getChildren(
            parentId = "playlist_$playlistId",
            page = 0,
            pageSize = 10,
            params = null,
            scope = testScope
        ).get(5, TimeUnit.SECONDS)

        val items = childrenResult.value ?: emptyList()
        assertEquals(1, items.size)
        val trackItem = items[0]
        assertEquals("track_indie_vibes::track_sub_1::0", trackItem.mediaId)
        assertEquals("Midnight City", trackItem.mediaMetadata.title?.toString())
        assertEquals("M83", trackItem.mediaMetadata.artist?.toString())
        assertEquals("Hurry Up, We're Dreaming", trackItem.mediaMetadata.albumTitle?.toString())
        assertTrue(trackItem.mediaMetadata.isPlayable == true)
        assertFalse(trackItem.mediaMetadata.isBrowsable == true)
        // Deezer artwork upgraded to cover_xl / 1000x1000
        val artStr = trackItem.mediaMetadata.artworkUri?.toString() ?: ""
        assertTrue("Artwork should be upgraded to high-res: $artStr", artStr.contains("cover_xl") || artStr.contains("1000x1000") || artStr.contains("cover_big"))
    }

    @Test
    fun getChildren_downloads_returnsCompletedOfflineTracksWithLocalArtwork() {
        val trackId = "offline_track_1"
        val track = TrackEntity(
            id = trackId,
            title = "Starboy",
            artist = "The Weeknd",
            album = "Starboy",
            durationMs = 230_000L,
            artworkUri = "https://example.com/starboy.jpg",
            artworkUrl = "https://example.com/starboy.jpg",
            resolutionState = ResolutionState.MATCHED,
            downloadState = DownloadState.DOWNLOADED
        )

        // Mock a cached local artwork file
        val artDir = LocalArtworkStore.getArtworkDirectory(context)
        val artFile = File(artDir, "${trackId}.jpg")
        artFile.writeText("fake-image-bytes")

        val download = OfflineDownloadEntity(
            downloadId = "dl_1",
            trackId = trackId,
            provider = "YOUTUBE",
            providerSourceId = "yt_starboy",
            canonicalUrl = "https://www.youtube.com/watch?v=starboy",
            relativeStorageKey = "tracks/starboy.opus",
            bucket = "PINNED",
            status = OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = 5_000_000L,
            durationMs = 230_000L,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L,
            lastAccessedAtEpochMs = 1000L
        )

        runBlocking {
            db.trackDao().insertTrack(track)
            db.offlineDownloadDao().upsert(download)
        }

        val childrenResult = mediaTreeProvider.getChildren(
            parentId = AndroidAutoMediaTreeProvider.MEDIA_ID_DOWNLOADS,
            page = 0,
            pageSize = 10,
            params = null,
            scope = testScope
        ).get(5, TimeUnit.SECONDS)

        val items = childrenResult.value ?: emptyList()
        assertEquals(1, items.size)
        val dlItem = items[0]
        assertEquals("download_$trackId", dlItem.mediaId)
        assertEquals("Starboy", dlItem.mediaMetadata.title?.toString())
        assertEquals("The Weeknd", dlItem.mediaMetadata.artist?.toString())
        assertEquals("Starboy", dlItem.mediaMetadata.albumTitle?.toString())
        assertTrue(dlItem.mediaMetadata.isPlayable == true)
        // Must use local file:// URI for offline playback in car without internet
        val artUri = dlItem.mediaMetadata.artworkUri?.toString() ?: ""
        assertTrue("Downloaded track artwork must be local file://: $artUri", artUri.startsWith("file://"))

        artFile.delete()
    }

    @Test
    fun buildQueueForMediaId_handlesTrackIdsWithUnderscoresRobustly() {
        val playlistId = "liked_songs"
        val trackId = "yt_special_id_with_underscores"

        runBlocking {
            db.playlistDao().insertPlaylist(
                PlaylistEntity(
                    playlistId = playlistId,
                    title = "Liked Songs",
                    sourceUrl = null,
                    artworkUri = null,
                    dateCreatedEpochMs = 1000L,
                    dateModifiedEpochMs = 2000L
                )
            )

            db.trackDao().insertTrack(
                TrackEntity(
                    id = trackId,
                    title = "Underscore Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 200_000L,
                    artworkUri = "https://example.com/art.jpg",
                    artworkUrl = "https://example.com/art.jpg",
                    resolutionState = ResolutionState.MATCHED,
                    downloadState = DownloadState.NOT_DOWNLOADED
                )
            )

            db.playlistDao().insertPlaylistEntries(
                listOf(
                    PlaylistEntryEntity(
                        entryId = 2L,
                        playlistId = playlistId,
                        trackId = trackId,
                        position = 0
                    )
                )
            )
        }

        // Test with :: delimiter
        val mediaIdWithColons = "track_${playlistId}::${trackId}::0"
        val (queue, index) = runBlocking { mediaTreeProvider.buildQueueForMediaId(mediaIdWithColons) }
        assertEquals(1, queue.size)
        assertEquals(0, index)
        assertEquals(trackId, queue[0].track.id.rawId)
        assertEquals("Underscore Song", queue[0].track.title)

        // Test getItem with :: delimiter
        val itemResult = mediaTreeProvider.getItem(mediaIdWithColons, testScope).get(5, TimeUnit.SECONDS)
        assertNotNull(itemResult)
        assertEquals("Underscore Song", itemResult.value?.mediaMetadata?.title?.toString())
    }

    @Test
    fun buildQueueForMediaId_likedSongsFolder_startsFromZero() {
        runBlocking {
            db.playlistDao().insertPlaylist(
                PlaylistEntity(
                    playlistId = PlaylistRepository.LIKED_SONGS_PLAYLIST_ID,
                    title = "Liked Songs",
                    sourceUrl = null,
                    artworkUri = null,
                    dateCreatedEpochMs = 1000L,
                    dateModifiedEpochMs = 2000L
                )
            )
            db.trackDao().insertTrack(
                TrackEntity(
                    id = "liked_1",
                    title = "Liked Song 1",
                    artist = "Artist 1",
                    album = "Album 1",
                    durationMs = 180_000L,
                    artworkUri = "https://example.com/art1.jpg",
                    artworkUrl = "https://example.com/art1.jpg",
                    resolutionState = ResolutionState.MATCHED,
                    downloadState = DownloadState.NOT_DOWNLOADED
                )
            )
            db.playlistDao().insertPlaylistEntries(
                listOf(
                    PlaylistEntryEntity(
                        entryId = 3L,
                        playlistId = PlaylistRepository.LIKED_SONGS_PLAYLIST_ID,
                        trackId = "liked_1",
                        position = 0
                    )
                )
            )
        }

        val (queue, targetIndex) = runBlocking { mediaTreeProvider.buildQueueForMediaId(AndroidAutoMediaTreeProvider.MEDIA_ID_LIKED_SONGS) }
        assertEquals(1, queue.size)
        assertEquals(0, targetIndex)
        assertEquals("liked_1", queue[0].track.id.rawId)
        assertEquals("Liked Song 1", queue[0].track.title)
    }
}
