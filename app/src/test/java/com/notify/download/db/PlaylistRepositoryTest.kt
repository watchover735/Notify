package com.notify.download.db

import android.content.Context
import androidx.room.Room
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {

    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun publicExtractorOutput_mappedToRoomEntities_preservesAll93PositionsAndInitialStates() = runTest {
        // Create 93 mock scraped tracks (matching real-device test)
        val mockTracks = (1..93).map { index ->
            ScrapedTrack(
                position = index,
                title = if (index == 1) "Born to Shine" else "Song Title $index",
                artist = if (index == 1) "Diljit Dosanjh" else "Artist $index",
                album = "Album $index",
                durationMs = 180000L + index * 1000,
                artworkUrl = "https://i.scdn.co/image/track_$index"
            )
        }

        val scrapedPlaylist = ScrapedPlaylist(
            id = "test_playlist_93",
            title = "Top Hits Punjabi",
            description = "Best tracks",
            artworkUrl = "https://i.scdn.co/image/playlist_cover",
            expectedTrackCount = 93,
            tracks = mockTracks
        )

        // 1. Save to Room
        val savedPlaylist = repository.saveScrapedPlaylist(scrapedPlaylist)
        assertEquals("pl_spotify_test_playlist_93", savedPlaylist.playlistId)
        assertEquals("Top Hits Punjabi", savedPlaylist.title)

        // 2. Query entries back from Room
        val entries = repository.getPlaylistEntries(savedPlaylist.playlistId)
        assertEquals(93, entries.size)

        // 3. Verify exact position preservation 1..93
        for (i in 0 until 93) {
            val entry = entries[i]
            assertEquals(i + 1, entry.position)
            if (i == 0) {
                assertEquals("Born to Shine", entry.title)
                assertEquals("Diljit Dosanjh", entry.artist)
            } else {
                assertEquals("Song Title ${i + 1}", entry.title)
            }
            // Verify initial split track state
            assertEquals(ResolutionState.METADATA_ONLY, entry.resolutionState)
            assertEquals(DownloadState.NOT_DOWNLOADED, entry.downloadState)
            assertNull("localContentUri must be null before download", entry.localContentUri)
        }
    }

    @Test
    fun duplicatePlaylistEntries_preservedAtDifferentPositions() = runTest {
        // Spotify playlists can contain the exact same song multiple times at different positions
        val tracksWithDuplicate = listOf(
            ScrapedTrack(1, "Repeat Song", "Artist A", "Album A", 200000L, null),
            ScrapedTrack(2, "Middle Song", "Artist B", "Album B", 180000L, null),
            ScrapedTrack(3, "Repeat Song", "Artist A", "Album A", 200000L, null)
        )

        val scraped = ScrapedPlaylist(
            id = "dup_playlist",
            title = "Playlist with Duplicates",
            description = null,
            artworkUrl = null,
            expectedTrackCount = 3,
            tracks = tracksWithDuplicate
        )

        val saved = repository.saveScrapedPlaylist(scraped)
        val entries = repository.getPlaylistEntries(saved.playlistId)

        assertEquals(3, entries.size)
        assertEquals(1, entries[0].position)
        assertEquals("Repeat Song", entries[0].title)

        assertEquals(2, entries[1].position)
        assertEquals("Middle Song", entries[1].title)

        assertEquals(3, entries[2].position)
        assertEquals("Repeat Song", entries[2].title)
    }

    @Test
    fun resolvedStreamUrl_neverPersistedInEntitiesOrRoom() = runTest {
        val scraped = ScrapedPlaylist(
            id = "security_test",
            title = "Security Test",
            description = null,
            artworkUrl = null,
            expectedTrackCount = 1,
            tracks = listOf(
                ScrapedTrack(1, "Born to Shine", "Diljit Dosanjh", "G.O.A.T.", 213000L, null)
            )
        )

        val saved = repository.saveScrapedPlaylist(scraped)
        val entry = repository.getPlaylistEntries(saved.playlistId).first()

        // Update resolution state to MATCHED
        repository.updateTrackResolution(entry.trackId, ResolutionState.MATCHED)

        // Save selected YouTube candidate source (canonical YouTube watch URL only)
        val canonicalYoutubeUrl = "https://www.youtube.com/watch?v=kJQP7kiw5Fk"
        val candidateSource = TrackSourceEntity(
            sourceKey = "${entry.trackId}:youtube",
            trackId = entry.trackId,
            provider = "youtube",
            sourceId = "kJQP7kiw5Fk",
            canonicalUrl = canonicalYoutubeUrl,
            confidence = 0.95f,
            durationDeltaMs = 500L,
            selected = true
        )
        repository.saveSelectedSource(candidateSource)

        // Query track and source back from Room
        val updatedTrack = db.trackDao().getTrackById(entry.trackId)
        val savedSource = repository.getSelectedSource(entry.trackId)

        assertNotNull(updatedTrack)
        assertEquals(ResolutionState.MATCHED, updatedTrack!!.resolutionState)
        assertNull("Signed stream URL must NEVER be saved in localContentUri", updatedTrack.localContentUri)

        assertNotNull(savedSource)
        assertEquals(canonicalYoutubeUrl, savedSource!!.canonicalUrl)
        assertTrue(
            "Canonical URL must NOT be a googlevideo or signed CDN stream URL",
            savedSource.canonicalUrl.startsWith("https://www.youtube.com/watch?v=")
        )
    }

    @Test
    fun saveTrackToLikedSongs_addsTrack_andNeverWritesToRecentSearchItems() = runTest {
        val videoId = "abc123xyz"
        val title = "Song of Joy"
        val artist = "Artist Name"

        // Initially not liked
        val initialLiked = repository.isTrackLiked("youtube", videoId)
        org.junit.Assert.assertFalse(initialLiked)

        // Save to liked songs
        repository.saveTrackToLikedSongs(
            title = title,
            artist = artist,
            album = "Album",
            durationMs = 210000L,
            artworkUrl = "https://i.ytimg.com/vi/abc123xyz/hqdefault.jpg",
            provider = "youtube",
            providerSourceId = videoId
        )

        // Verify liked status
        val isLikedAfter = repository.isTrackLiked("youtube", videoId)
        assertTrue(isLikedAfter)

        // Verify that recent_search_items table was NOT written
        val recents = db.searchHistoryDao().getRecentMediaItems()
        assertTrue("Saving to Liked Songs must NEVER write to recent_search_items", recents.isEmpty())

        // Verify playlist entry exists in liked_songs
        val entries = repository.getPlaylistEntries(PlaylistRepository.LIKED_SONGS_PLAYLIST_ID)
        assertEquals(1, entries.size)
        assertEquals("youtube:$videoId", entries[0].trackId)

        // Remove from liked songs
        repository.removeTrackFromLikedSongs("youtube", videoId)
        val isLikedRemoved = repository.isTrackLiked("youtube", videoId)
        org.junit.Assert.assertFalse(isLikedRemoved)
    }
}

