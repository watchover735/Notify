package com.notify.download.worker

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.notify.download.db.ArtworkEnrichmentStatus
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.TrackEntity
import com.notify.download.matcher.YouTubeCandidate
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
class ArtworkEnrichmentWorkerTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    private val PLAYLIST_ID = "test_playlist_worker"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
        NotiFyDatabase.setTestInstance(db)
    }

    @After
    fun teardown() {
        ArtworkEnrichmentWorker.enricherOverride = null
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun worker_withoutPlaylistId_returnsFailure() = runTest {
        val worker = TestListenableWorkerBuilder<ArtworkEnrichmentWorker>(context)
            .setInputData(workDataOf())
            .build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun worker_withEmptyNeedingTracks_returnsSuccess() = runTest {
        db.playlistDao().insertPlaylist(
            PlaylistEntity(playlistId = PLAYLIST_ID, title = "Empty Playlist")
        )

        val worker = TestListenableWorkerBuilder<ArtworkEnrichmentWorker>(context)
            .setInputData(workDataOf(ArtworkEnrichmentWorker.KEY_PLAYLIST_ID to PLAYLIST_ID))
            .build()

        val result = worker.doWork()
        assertTrue("Result must be success", result is ListenableWorker.Result.Success)
    }

    @Test
    fun worker_enrichesTracksProgressively_andUpdatesDatabase() = runTest {
        db.playlistDao().insertPlaylist(
            PlaylistEntity(playlistId = PLAYLIST_ID, title = "Work Playlist")
        )
        db.trackDao().insertTracks(
            listOf(
                TrackEntity(id = "wt_1", title = "Song 1", artist = "Artist 1", durationMs = 200_000L),
                TrackEntity(id = "wt_2", title = "Song 2", artist = "Artist 2", durationMs = 180_000L)
            )
        )
        db.playlistDao().insertPlaylistEntries(
            listOf(
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "wt_1", position = 0),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "wt_2", position = 1)
            )
        )

        // Mock enricher override
        val fakeEnricher = object : ArtworkEnricher {
            override suspend fun enrichPlaylist(
                playlistId: String,
                forceRetry: Boolean,
                onProgress: suspend (completed: Int, total: Int, failed: Int) -> Unit
            ): EnrichmentResult {
                repository.updateTrackEnrichment(
                    trackId = "wt_1",
                    candidate = YouTubeCandidate(
                        videoId = "vid_wt_1",
                        title = "Song 1",
                        channelTitle = "Artist 1",
                        durationMs = 200_000L,
                        artworkUrl = "https://art/wt_1.jpg"
                    ),
                    isMatchSafe = true
                )
                repository.updateTrackEnrichment(
                    trackId = "wt_2",
                    candidate = YouTubeCandidate(
                        videoId = "vid_wt_2",
                        title = "Song 2",
                        channelTitle = "Artist 2",
                        durationMs = 180_000L,
                        artworkUrl = "https://art/wt_2.jpg"
                    ),
                    isMatchSafe = true
                )
                onProgress(2, 2, 0)
                return EnrichmentResult(completed = 2, total = 2, failed = 0)
            }
        }
        ArtworkEnrichmentWorker.enricherOverride = fakeEnricher

        val worker = TestListenableWorkerBuilder<ArtworkEnrichmentWorker>(context)
            .setInputData(workDataOf(ArtworkEnrichmentWorker.KEY_PLAYLIST_ID to PLAYLIST_ID))
            .build()

        val result = worker.doWork()
        assertTrue("Result must be success", result is ListenableWorker.Result.Success)

        val track1 = db.trackDao().getTrackById("wt_1")
        val track2 = db.trackDao().getTrackById("wt_2")

        assertNotNull(track1)
        assertEquals("https://art/wt_1.jpg", track1!!.artworkUri)
        assertEquals(ArtworkEnrichmentStatus.ENRICHED, track1.artworkEnrichmentStatus)

        assertNotNull(track2)
        assertEquals("https://art/wt_2.jpg", track2!!.artworkUri)
        assertEquals(ArtworkEnrichmentStatus.ENRICHED, track2.artworkEnrichmentStatus)
    }
}
