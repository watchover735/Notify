package com.notify.download.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class YouTubePlaylistImportTest {

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
    fun saveYouTubePlaylist_batchInsertsMoreThan100Tracks_preservesAllMetadata() = runTest {
        val trackCount = 125 // > 100 to test chunking
        val mockTracks = (1..trackCount).map { index ->
            YouTubePlaylistTrack(
                videoId = "vid_$index",
                title = "YouTube Song $index",
                artist = "YouTube Artist $index",
                durationMs = 200_000L + index * 500,
                artworkUrl = "https://i.ytimg.com/vi/vid_$index/hqdefault.jpg",
                position = index
            )
        }

        val playlist = repository.saveYouTubePlaylist(
            playlistTitle = "My Punjabi Hits",
            youtubePlaylistId = "PLtest_punjabi_123",
            tracks = mockTracks,
            artworkUrl = "https://i.ytimg.com/vi/vid_1/hqdefault.jpg",
            sourceUrl = "https://www.youtube.com/playlist?list=PLtest_punjabi_123"
        )

        assertNotNull(playlist)
        assertEquals("pl_youtube_PLtest_punjabi_123", playlist.playlistId)
        assertEquals("My Punjabi Hits", playlist.title)
        assertTrue(playlist.sourceUrl?.contains("youtube") == true)

        // Verify entries in Room
        val entries = db.playlistDao().observePlaylistEntries(playlist.playlistId).first()
        assertEquals(trackCount, entries.size)
        assertEquals("YouTube Song 1", entries[0].title)
        assertEquals(1, entries[0].position)
        assertEquals(trackCount, entries[trackCount - 1].position)

        // Verify track details: matched state and YouTube artwork origin
        val firstTrack = db.trackDao().getTrackById("youtube:vid_1")
        assertNotNull(firstTrack)
        assertEquals(ResolutionState.MATCHED, firstTrack?.resolutionState)
        assertEquals(ArtworkOrigin.YOUTUBE_MATCH, firstTrack?.artworkOrigin)
        assertEquals("https://i.ytimg.com/vi/vid_1/hqdefault.jpg", firstTrack?.artworkUrl)

        // Verify track_source was inserted
        val source = db.trackDao().getSelectedSource("youtube:vid_1")
        assertNotNull(source)
        assertEquals("vid_1", source?.sourceId)
        assertEquals("YOUTUBE", source?.provider)
        assertTrue(source?.selected == true)

        // Verify PlaylistSummary
        val summaries = repository.getPlaylistSummaries()
        val summary = summaries.find { it.playlistId == playlist.playlistId }
        assertNotNull(summary)
        assertEquals(trackCount, summary?.trackCount)
    }
}
