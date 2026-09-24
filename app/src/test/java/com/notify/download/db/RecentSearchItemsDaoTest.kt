package com.notify.download.db

import android.content.Context
import androidx.room.Room
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

/**
 * Unit tests for Spotify-style Recent Media Items persistence in [SearchHistoryDao],
 * satisfying tests 10–15 of Patch A2.1.2:
 *
 * 10. Tapped/played result is recorded into recent media items.
 * 11. Submitting plain search text alone does NOT create a visible recent media item.
 * 12. Selecting duplicate song moves it to the top without duplicate rows.
 * 13. 16 unique items prune the oldest, leaving exactly 15 items.
 * 14. Direct play routes through provider + providerSourceId (preserves provider & source ID).
 * 15. Individual deletion and Clear All actions remove recents without touching other tables.
 */
@RunWith(RobolectricTestRunner::class)
class RecentSearchItemsDaoTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var dao: SearchHistoryDao

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.searchHistoryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── 10. Tapped/played result recorded ───────────────────────────────────

    @Test
    fun recordRecentItem_persistsMediaItemWithStableId() = runTest {
        val item = RecentSearchItemEntity.create(
            provider = "youtube_music_innertube",
            providerSourceId = "video_abc_123",
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            durationMs = 214_000L,
            artworkUrl = "https://i.ytimg.com/vi/video_abc_123/hqdefault.jpg"
        )
        dao.recordRecentItem(item)

        val recents = dao.getRecentMediaItems(15)
        assertEquals("Should have exactly 1 recent media item", 1, recents.size)
        assertEquals("youtube_music_innertube:video_abc_123", recents[0].id)
        assertEquals("Born to Shine", recents[0].title)
        assertEquals("Diljit Dosanjh", recents[0].artist)
        assertEquals("youtube_music_innertube", recents[0].provider)
        assertEquals("video_abc_123", recents[0].providerSourceId)
        assertEquals("https://i.ytimg.com/vi/video_abc_123/hqdefault.jpg", recents[0].artworkUrl)
    }

    // ── 11. Submitting text alone does NOT create a visible recent media item ─

    @Test
    fun textSubmissionAlone_doesNotCreateRecentMediaItem() = runTest {
        // User types and submits text query "born to shine"
        dao.recordSearch("born to shine")

        // Query history has the entry
        val queryHistory = dao.getRecentSearches()
        assertEquals("Query history should contain the search text", 1, queryHistory.size)

        // BUT recent media items (the Spotify-style song list) MUST remain empty!
        val mediaItems = dao.getRecentMediaItems(15)
        assertEquals("Media items list must be completely empty when only text query is submitted", 0, mediaItems.size)
    }

    // ── 12. Duplicate selection moves to top without duplication ────────────

    @Test
    fun duplicateSelection_movesToTopWithoutDuplication() = runTest {
        val song1 = RecentSearchItemEntity.create(
            provider = "youtube",
            providerSourceId = "id_1",
            title = "Song One",
            artist = "Artist A"
        )
        val song2 = RecentSearchItemEntity.create(
            provider = "youtube",
            providerSourceId = "id_2",
            title = "Song Two",
            artist = "Artist B"
        )

        // Select song 1, then song 2
        dao.recordRecentItem(song1)
        Thread.sleep(10)
        dao.recordRecentItem(song2)

        var recents = dao.getRecentMediaItems(15)
        assertEquals(2, recents.size)
        assertEquals("youtube:id_2", recents[0].id)
        assertEquals("youtube:id_1", recents[1].id)

        // Re-select song 1 (same provider and sourceId)
        Thread.sleep(10)
        val song1Again = RecentSearchItemEntity.create(
            provider = "youtube",
            providerSourceId = "id_1",
            title = "Song One (Updated Title)",
            artist = "Artist A"
        )
        dao.recordRecentItem(song1Again)

        recents = dao.getRecentMediaItems(15)
        assertEquals("Must NOT create duplicate rows for same provider:sourceId", 2, recents.size)
        assertEquals("Song 1 must have moved to the top", "youtube:id_1", recents[0].id)
        assertEquals("Song 1 title must be updated", "Song One (Updated Title)", recents[0].title)
        assertEquals("Song 2 is now second", "youtube:id_2", recents[1].id)
    }

    // ── 13. 16 unique items prune oldest, leaving exactly 15 ────────────────

    @Test
    fun sixteenUniqueItems_prunesOldestLeavingExactly15() = runTest {
        for (i in 1..16) {
            val item = RecentSearchItemEntity.create(
                provider = "youtube",
                providerSourceId = "source_$i",
                title = "Track $i",
                artist = "Artist"
            )
            dao.recordRecentItem(item)
            Thread.sleep(5) // ensure distinct timestamps
        }

        val recents = dao.getRecentMediaItems(15)
        assertEquals("Must contain exactly 15 items after inserting 16", 15, recents.size)

        val ids = recents.map { it.providerSourceId }
        assertTrue("Newest item (16) must be present", ids.contains("source_16"))
        assertTrue("Second item (2) must be present", ids.contains("source_2"))
        assertTrue("Oldest item (1) must have been pruned", !ids.contains("source_1"))
    }

    // ── 14. Direct play routes through provider + providerSourceId ──────────

    @Test
    fun recentItem_preservesProviderAndSourceId_forDirectPlayback() = runTest {
        val item = RecentSearchItemEntity.create(
            provider = "spotify",
            providerSourceId = "spotify_track_456",
            title = "G.O.A.T.",
            artist = "Diljit Dosanjh",
            durationMs = 223_000L
        )
        dao.recordRecentItem(item)

        val retrieved = dao.getRecentMediaItem(RecentSearchItemEntity.recentItemId("spotify", "spotify_track_456"))
        assertNotNull("Should retrieve saved recent item", retrieved)
        assertEquals("spotify", retrieved!!.provider)
        assertEquals("spotify_track_456", retrieved.providerSourceId)
        // Direct stream resolution uses provider + providerSourceId without blindly assuming YouTube URL
        assertEquals("spotify:spotify_track_456", retrieved.id)
    }

    // ── 15. Add/remove actions ──────────────────────────────────────────────

    @Test
    fun deleteRecentMediaItem_removesOnlySpecificItem() = runTest {
        val songA = RecentSearchItemEntity.create("yt", "a", "Song A", "Artist")
        val songB = RecentSearchItemEntity.create("yt", "b", "Song B", "Artist")
        dao.recordRecentItem(songA)
        dao.recordRecentItem(songB)

        assertEquals(2, dao.getRecentMediaItems(15).size)

        // Remove only song A
        dao.deleteRecentMediaItem(songA.id)

        val remaining = dao.getRecentMediaItems(15)
        assertEquals(1, remaining.size)
        assertEquals(songB.id, remaining[0].id)
    }

    @Test
    fun clearAllRecentMediaItems_clearsOnlyRecents_preservesPlaylistsAndTracks() = runTest {
        // Seed playlist and track
        val playlistDao = db.playlistDao()
        val trackDao = db.trackDao()

        val playlist = PlaylistEntity(
            playlistId = "playlist_1",
            title = "My Playlist"
        )
        playlistDao.insertPlaylist(playlist)

        val track = TrackEntity(
            id = "track_1",
            title = "Playlist Track",
            artist = "Artist",
            durationMs = 180_000L
        )
        trackDao.insertTrack(track)

        // Add recent media items
        dao.recordRecentItem(RecentSearchItemEntity.create("yt", "rec_1", "Recent 1", "Artist"))
        dao.recordRecentItem(RecentSearchItemEntity.create("yt", "rec_2", "Recent 2", "Artist"))

        assertEquals(2, dao.getRecentMediaItems(15).size)
        assertEquals(1, playlistDao.getAllPlaylists().size)
        assertNotNull(trackDao.getTrackById("track_1"))

        // Clear all recents
        dao.clearAllRecentMediaItems()

        // Recents are gone
        assertEquals(0, dao.getRecentMediaItems(15).size)

        // Playlists and tracks are completely unaffected!
        assertEquals("Playlists must remain intact", 1, playlistDao.getAllPlaylists().size)
        assertNotNull("Track must remain intact", trackDao.getTrackById("track_1"))
    }

    // ── 16. Cross-context playback deduplication (Jailer from Playlist then Search) ──

    @Test
    fun jailer_playedFromPlaylistThenSearch_leavesExactlyOneRow() = runTest {
        // Step 1: "Jailer" is played from a Spotify playlist.
        // It was matched to YouTube video "abc123xyz".
        // The centralized playback recorder writes using canonical key "youtube:abc123xyz" as the id.
        val playlistPlay = RecentSearchItemEntity(
            id = "youtube:abc123xyz",
            provider = "YOUTUBE",
            providerSourceId = "abc123xyz",
            catalogTrackId = "spotify_jailer_track_id",
            title = "Jailer",
            artist = "Anirudh Ravichander",
            album = "Jailer OST",
            durationMs = 210_000L,
            artworkUrl = "https://example.com/jailer.jpg",
            sourceContext = "PLAYBACK"
        )
        dao.recordRecentItem(playlistPlay)

        val recentsAfterPlaylist = dao.getRecentMediaItems(15)
        assertEquals(1, recentsAfterPlaylist.size)
        assertEquals("youtube:abc123xyz", recentsAfterPlaylist[0].id)
        assertEquals("spotify_jailer_track_id", recentsAfterPlaylist[0].catalogTrackId)

        // Step 2: User subsequently searches for "Jailer" and plays it directly from search results.
        // The resolved YouTube video ID is "abc123xyz".
        // The centralized playback recorder writes using canonical key "youtube:abc123xyz" as the id.
        val searchPlay = RecentSearchItemEntity(
            id = "youtube:abc123xyz",
            provider = "YOUTUBE",
            providerSourceId = "abc123xyz",
            catalogTrackId = null,
            title = "Jailer",
            artist = "Anirudh Ravichander",
            album = "Jailer OST",
            durationMs = 210_000L,
            artworkUrl = "https://example.com/jailer.jpg",
            sourceContext = "PLAYBACK"
        )
        dao.recordRecentItem(searchPlay)

        // Acceptance Criterion: Exactly ONE row must exist in Recents!
        val recentsAfterSearch = dao.getRecentMediaItems(15)
        assertEquals("Cross-context playback of same song must leave exactly ONE row", 1, recentsAfterSearch.size)
        assertEquals("youtube:abc123xyz", recentsAfterSearch[0].id)
    }
}
