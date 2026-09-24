package com.notify.download.db

import android.content.Context
import androidx.room.Room
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import kotlinx.coroutines.flow.first
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
class PlaylistDaoTest {

    private lateinit var db: NotiFyDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var trackDao: TrackDao
    private lateinit var repository: PlaylistRepository

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
        trackDao = db.trackDao()
        repository = PlaylistRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun observePlaylistSummaries_returnsCorrectCountsAndFirstTrackArtwork() = runTest {
        // Given a playlist with 2 tracks
        val playlist = PlaylistEntity(
            playlistId = "pl_test_1",
            title = "My Playlist",
            sourceUrl = null,
            artworkUri = null,
            dateCreatedEpochMs = 1000L,
            dateModifiedEpochMs = 2000L
        )
        playlistDao.insertPlaylist(playlist)

        val track1 = TrackEntity(
            id = "t1",
            title = "Track 1",
            artist = "Artist 1",
            artworkUrl = "https://example.com/art1.jpg"
        )
        val track2 = TrackEntity(
            id = "t2",
            title = "Track 2",
            artist = "Artist 2",
            artworkUrl = "https://example.com/art2.jpg"
        )
        trackDao.insertTracks(listOf(track1, track2))

        val entries = listOf(
            PlaylistEntryEntity(playlistId = "pl_test_1", trackId = "t1", position = 1),
            PlaylistEntryEntity(playlistId = "pl_test_1", trackId = "t2", position = 2)
        )
        playlistDao.insertPlaylistEntries(entries)

        // When observing summaries
        val summaries = playlistDao.observePlaylistSummaries().first()

        // Then
        assertEquals(1, summaries.size)
        val summary = summaries[0]
        assertEquals("pl_test_1", summary.playlistId)
        assertEquals("My Playlist", summary.title)
        assertEquals(2, summary.trackCount)
        assertEquals("https://example.com/art1.jpg", summary.firstTrackArtworkUrl)
    }

    @Test
    fun create_rename_andDeletePlaylist_cascadesEntriesCorrectly() = runTest {
        // 1. Create playlist
        val created = repository.createPlaylist("Favorites")
        assertNotNull(created.playlistId)
        assertEquals("Favorites", created.title)

        // Add an entry
        val track = TrackEntity(id = "tr_fav", title = "Favorite Song", artist = "Star")
        trackDao.insertTrack(track)
        playlistDao.insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = created.playlistId, trackId = "tr_fav", position = 1))
        )

        var entries = repository.getPlaylistEntries(created.playlistId)
        assertEquals(1, entries.size)

        // 2. Rename playlist
        repository.renamePlaylist(created.playlistId, "Top Favorites")
        val renamed = repository.getPlaylistById(created.playlistId)
        assertEquals("Top Favorites", renamed?.title)

        // 3. Delete playlist with confirmation
        repository.deletePlaylist(created.playlistId)
        val afterDelete = repository.getPlaylistById(created.playlistId)
        assertNull(afterDelete)

        // Entries must be cascade deleted
        entries = repository.getPlaylistEntries(created.playlistId)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun reimportingSameSpotifyPlaylist_preservesTrackMatchesAndSourcesWithoutDuplicates() = runTest {
        // 1. Initial import of Spotify playlist
        val tracksInitial = listOf(
            ScrapedTrack(1, "Born to Shine", "Diljit Dosanjh", "G.O.A.T.", 213000L, "https://art.scdn.co/1"),
            ScrapedTrack(2, "Clash", "Diljit Dosanjh", "G.O.A.T.", 190000L, "https://art.scdn.co/2")
        )
        val scraped = ScrapedPlaylist(
            id = "spotify_goat",
            title = "G.O.A.T. Hits",
            description = null,
            artworkUrl = "https://art.scdn.co/cover",
            expectedTrackCount = 2,
            tracks = tracksInitial
        )
        repository.saveScrapedPlaylist(scraped)

        val entries1 = repository.getPlaylistEntries("pl_spotify_spotify_goat")
        assertEquals(2, entries1.size)
        val bornTrackId = entries1[0].trackId

        // 2. Simulate track 1 getting matched and having a selected TrackSourceEntity
        repository.updateTrackResolution(bornTrackId, ResolutionState.MATCHED)
        val source = TrackSourceEntity(
            sourceKey = "$bornTrackId:youtube",
            trackId = bornTrackId,
            provider = "youtube",
            sourceId = "kJQP7kiw5Fk",
            canonicalUrl = "https://www.youtube.com/watch?v=kJQP7kiw5Fk",
            confidence = 0.98f,
            durationDeltaMs = 300L,
            artworkUrl = "https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg",
            selected = true
        )
        repository.saveSelectedSource(source)

        // Verify source is saved and track is MATCHED
        assertEquals(ResolutionState.MATCHED, trackDao.getTrackById(bornTrackId)?.resolutionState)
        assertNotNull(repository.getSelectedSource(bornTrackId))

        // 3. Re-import the exact same Spotify playlist (e.g. user taps import again or refreshes)
        repository.saveScrapedPlaylist(scraped)

        // 4. Verify no duplicate playlist was created
        val allPlaylists = playlistDao.getAllPlaylists()
        assertEquals(1, allPlaylists.size)
        assertEquals("pl_spotify_spotify_goat", allPlaylists[0].playlistId)

        // 5. Verify existing track resolution state was NOT wiped out
        val bornTrackAfterReimport = trackDao.getTrackById(bornTrackId)
        assertNotNull(bornTrackAfterReimport)
        assertEquals("Track must remain MATCHED after re-import", ResolutionState.MATCHED, bornTrackAfterReimport!!.resolutionState)

        // 6. Verify TrackSourceEntity was NOT cascade-deleted on re-import
        val sourceAfterReimport = repository.getSelectedSource(bornTrackId)
        assertNotNull("TrackSourceEntity must be preserved across re-imports", sourceAfterReimport)
        assertEquals("https://www.youtube.com/watch?v=kJQP7kiw5Fk", sourceAfterReimport!!.canonicalUrl)
    }
}
