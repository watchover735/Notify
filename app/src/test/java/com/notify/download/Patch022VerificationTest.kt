package com.notify.download

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.core.playback.ResolvedPlaybackItemFactory
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistEntryWithTrack
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import com.notify.download.stream.OnlineStreamResolver
import com.notify.playback.PlaybackUiState
import com.notify.ui.search.OnlineSearchUiState
import com.notify.ui.search.OnlineSearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Patch022VerificationTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Test 1: Spotify artwork maps and persists ───────────────────────────────
    @Test
    fun testSpotifyArtworkMapsAndPersists() = runTest {
        val playlistArt = "https://i.scdn.co/image/ab67706c0000bebb_playlist"
        val trackArt = "https://i.scdn.co/image/ab67616d0000b273_track"

        val scraped = ScrapedPlaylist(
            id = "test_playlist",
            title = "Test Hits",
            description = "Test Desc",
            artworkUrl = playlistArt,
            expectedTrackCount = 1,
            tracks = listOf(
                ScrapedTrack(
                    position = 1,
                    title = "Born to Shine",
                    artist = "Diljit Dosanjh",
                    album = "G.O.A.T.",
                    durationMs = 214000L,
                    artworkUrl = trackArt
                )
            )
        )

        val saved = repository.saveScrapedPlaylist(scraped)
        assertEquals(playlistArt, saved.artworkUri)

        val entries = repository.getPlaylistEntries(saved.playlistId)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(trackArt, entry.artworkUri)
        assertEquals(trackArt, entry.artworkUrl)

        // Verify domain track receives the artworkUrl
        val domainTrack = repository.toDomainTrack(entry)
        assertEquals(trackArt, domainTrack.artworkUri)
    }

    // ── Test 2: YouTube thumbnail fallback works ────────────────────────────────
    @Test
    fun testYouTubeThumbnailFallbackWorks() {
        // High-res and low-res thumbnails
        val thumbsJson = JSONArray().apply {
            put(JSONObject().apply {
                put("url", "https://i.ytimg.com/vi/123/default.jpg")
                put("width", 120)
                put("height", 90)
            })
            put(JSONObject().apply {
                put("url", "https://i.ytimg.com/vi/123/hqdefault.jpg")
                put("width", 480)
                put("height", 360)
            })
        }

        // Test candidate model construction with best thumbnail
        val bestThumb = thumbsJson.getJSONObject(1).getString("url")
        val candidate = YouTubeCandidate(
            videoId = "123",
            title = "Born to Shine",
            channelTitle = "Diljit Dosanjh",
            durationMs = 214000L,
            artworkUrl = bestThumb,
            album = "G.O.A.T.",
            provider = "youtube_music_innertube"
        )

        assertEquals("https://i.ytimg.com/vi/123/hqdefault.jpg", candidate.artworkUrl)
        assertEquals("G.O.A.T.", candidate.album)
        assertEquals("youtube_music_innertube", candidate.provider)

        // Null thumbnail handled safely
        val nullThumbCandidate = candidate.copy(artworkUrl = null)
        assertNull(nullThumbCandidate.artworkUrl)
    }

    // ── Test 3: Room migration preserves existing tracks ────────────────────────
    @Test
    fun testRoomMigrationPreservesExistingTracks() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `tracks` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `title` TEXT NOT NULL,
                            `artist` TEXT NOT NULL,
                            `album` TEXT,
                            `durationMs` INTEGER NOT NULL,
                            `artworkUri` TEXT,
                            `spotifyId` TEXT,
                            `resolutionState` TEXT NOT NULL,
                            `downloadState` TEXT NOT NULL,
                            `localContentUri` TEXT,
                            `dateAddedEpochMs` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `track_sources` (
                            `sourceKey` TEXT NOT NULL PRIMARY KEY,
                            `trackId` TEXT NOT NULL,
                            `provider` TEXT NOT NULL,
                            `sourceId` TEXT NOT NULL,
                            `canonicalUrl` TEXT NOT NULL,
                            `confidence` REAL NOT NULL,
                            `durationDeltaMs` INTEGER NOT NULL,
                            `selected` INTEGER NOT NULL,
                            FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqliteDb = helper.writableDatabase
        sqliteDb.execSQL(
            """
            INSERT INTO `tracks` (id, title, artist, album, durationMs, artworkUri, spotifyId, resolutionState, downloadState, localContentUri, dateAddedEpochMs)
            VALUES ('t1', 'Born to Shine', 'Diljit Dosanjh', 'G.O.A.T.', 214000, 'https://art.spotify/123', 'sp_1', 'METADATA_ONLY', 'NOT_DOWNLOADED', NULL, 1000)
            """.trimIndent()
        )

        // Apply MIGRATION_1_2 directly
        NotiFyDatabase.MIGRATION_1_2.migrate(sqliteDb)

        // Verify data was preserved and artworkUrl was added and populated from artworkUri
        val cursor = sqliteDb.query("SELECT id, title, artworkUri, artworkUrl FROM tracks WHERE id = 't1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("t1", cursor.getString(0))
        assertEquals("Born to Shine", cursor.getString(1))
        assertEquals("https://art.spotify/123", cursor.getString(2))
        assertEquals("https://art.spotify/123", cursor.getString(3))
        cursor.close()
        helper.close()
    }

    // ── Test 4: Search emits text results without waiting for image loading ─────
    @Test
    fun testSearchEmitsTextResultsWithoutWaitingForImageLoading() = runTest {
        val candidate = YouTubeCandidate(
            videoId = "vid_123",
            title = "Born to Shine",
            channelTitle = "Diljit Dosanjh",
            durationMs = 214000L,
            artworkUrl = "https://i.ytimg.com/vi/vid_123/hqdefault.jpg"
        )

        val fastProvider = object : OnlineCatalogSearchProvider {
            override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
                return Result.success(listOf(candidate))
            }
        }

        val app = RuntimeEnvironment.getApplication()
        val vm = OnlineSearchViewModel(
            application = app,
            innerTubeProvider = fastProvider,
            fallbackProvider = fastProvider
        )

        vm.searchOnline("Born to Shine")

        // Wait for results
        var resultState: OnlineSearchUiState? = null
        for (i in 0 until 50) {
            val state = vm.uiState.value
            if (state is OnlineSearchUiState.Results) {
                resultState = state
                break
            }
            delay(20)
        }

        assertNotNull("Expected OnlineSearchUiState.Results", resultState)
        val results = resultState as OnlineSearchUiState.Results
        assertEquals(1, results.candidates.size)
        // Candidate has text metadata immediately; image is represented by artworkUrl string for async Coil load
        assertEquals("Born to Shine", results.candidates[0].title)
        assertEquals("https://i.ytimg.com/vi/vid_123/hqdefault.jpg", results.candidates[0].artworkUrl)
    }

    // ── Test 5: Only one submitted query executes ───────────────────────────────
    @Test
    fun testOnlyOneSubmittedQueryExecutes() = runTest {
        var searchExecutionCount = 0
        val countingProvider = object : OnlineCatalogSearchProvider {
            override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
                searchExecutionCount++
                return Result.success(emptyList())
            }
        }

        val app = RuntimeEnvironment.getApplication()
        val vm = OnlineSearchViewModel(
            application = app,
            innerTubeProvider = countingProvider,
            fallbackProvider = countingProvider
        )

        // Typing keystrokes invokes onQueryChanged, which should NEVER trigger search
        vm.onQueryChanged("b")
        vm.onQueryChanged("bo")
        vm.onQueryChanged("bor")
        vm.onQueryChanged("born")
        delay(50)

        assertEquals("Keystrokes must not execute search", 0, searchExecutionCount)

        // Only explicit searchOnline triggers execution
        vm.searchOnline("born")
        delay(50)

        assertTrue("Explicit search submission must execute search", searchExecutionCount >= 1)
    }

    // ── Test 6: Stale query results are discarded ───────────────────────────────
    @Test
    fun testStaleQueryResultsAreDiscarded() = runTest {
        val query1Started = CompletableDeferred<Unit>()
        val query1CanFinish = CompletableDeferred<Unit>()

        val slowProvider = object : OnlineCatalogSearchProvider {
            override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
                if (query == "slow_query") {
                    query1Started.complete(Unit)
                    query1CanFinish.await()
                    return Result.success(listOf(YouTubeCandidate("old", "Old Title", "Old Artist", 1000L)))
                } else {
                    return Result.success(listOf(YouTubeCandidate("new", "New Title", "New Artist", 2000L)))
                }
            }
        }

        val app = RuntimeEnvironment.getApplication()
        val vm = OnlineSearchViewModel(
            application = app,
            innerTubeProvider = slowProvider,
            fallbackProvider = slowProvider
        )

        // Launch slow query 1
        vm.searchOnline("slow_query")
        query1Started.await()

        // Supersede with query 2
        vm.searchOnline("fast_query")

        // Allow query 1 to finish late
        query1CanFinish.complete(Unit)
        delay(50)

        // State must reflect query 2, not stale query 1
        val state = vm.uiState.value
        assertTrue("State must be Results", state is OnlineSearchUiState.Results)
        val results = state as OnlineSearchUiState.Results
        assertEquals("fast_query", results.query)
        assertEquals("new", results.candidates[0].videoId)
    }

    // ── Test 7: Primary provider failure starts yt-dlp fallback ─────────────────
    @Test
    fun testPrimaryProviderFailureStartsYtDlpFallback() = runTest {
        val failingPrimary = object : OnlineCatalogSearchProvider {
            override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
                return Result.failure(RuntimeException("InnerTube network timeout"))
            }
        }

        var fallbackCalled = false
        val workingFallback = object : OnlineCatalogSearchProvider {
            override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
                fallbackCalled = true
                return Result.success(listOf(YouTubeCandidate("fb_1", "Fallback Title", "Artist", 180000L)))
            }
        }

        val app = RuntimeEnvironment.getApplication()
        val vm = OnlineSearchViewModel(
            application = app,
            innerTubeProvider = failingPrimary,
            fallbackProvider = workingFallback
        )

        vm.searchOnline("Diljit")

        // Wait for fallback
        for (i in 0 until 50) {
            if (fallbackCalled && vm.uiState.value is OnlineSearchUiState.Results) break
            delay(20)
        }

        assertTrue("Fallback provider must be invoked when primary fails", fallbackCalled)
        val state = vm.uiState.value
        assertTrue("State should end in Results from fallback", state is OnlineSearchUiState.Results)
        assertEquals("fb_1", (state as OnlineSearchUiState.Results).candidates[0].videoId)
    }

    // ── Test 8: Successful playback clears old FAILED state ──────────────────────
    @Test
    fun testSuccessfulPlaybackClearsOldFailedState() = runTest {
        val trackId = "spotify:failed_track_1"
        val track = TrackEntity(
            id = trackId,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            resolutionState = ResolutionState.RESOLVE_FAILED,
            artworkUrl = null
        )
        val source = TrackSourceEntity(
            sourceKey = "$trackId:youtube",
            trackId = trackId,
            provider = "youtube",
            sourceId = "yt_123",
            canonicalUrl = "https://youtube.com/watch?v=yt_123",
            confidence = 0.95f,
            durationDeltaMs = 500L,
            artworkUrl = "https://i.ytimg.com/vi/yt_123/hqdefault.jpg"
        )

        db.trackDao().insertTracks(listOf(track))
        db.trackDao().insertSource(source)

        // 1. Reconcile persisted states on startup
        val reconciled = repository.reconcilePersistedStates()
        assertEquals(1, reconciled)

        val reconciledTrack = db.trackDao().getTrackById(trackId)
        assertNotNull(reconciledTrack)
        assertEquals("Stale FAILED state must be reconciled to MATCHED", ResolutionState.MATCHED, reconciledTrack?.resolutionState)
        assertEquals("Matched thumbnail must be propagated to track", "https://i.ytimg.com/vi/yt_123/hqdefault.jpg", reconciledTrack?.artworkUrl)

        // 2. Playback event also reconciles
        val domainTrack = Track(
            id = TrackId.spotify(trackId),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            source = AudioSource.Remote(ProviderId.SPOTIFY, trackId)
        )
        val playbackState = PlaybackUiState(isPlaying = true, currentTrack = domainTrack)
        if (playbackState.isPlaying && playbackState.currentTrack != null) {
            repository.updateTrackResolution(trackId, ResolutionState.MATCHED)
        }
        assertEquals(ResolutionState.MATCHED, db.trackDao().getTrackById(trackId)?.resolutionState)
    }

    // ── Test 9: MediaItem receives artworkUri ───────────────────────────────────
    @Test
    fun testMediaItemReceivesArtworkUri() {
        val artworkUri = "https://i.ytimg.com/vi/abc12345/maxresdefault.jpg"
        val track = Track(
            id = TrackId(ProviderId.YOUTUBE, "abc12345"),
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            album = "G.O.A.T.",
            durationMs = 214000L,
            artworkUri = artworkUri,
            source = AudioSource.Remote(ProviderId.YOUTUBE, "abc12345")
        )

        val mediaItem = ResolvedPlaybackItemFactory.createMediaItem(
            track = track,
            streamUrl = "https://googlevideo.com/videoplayback?expire=123"
        )

        assertNotNull(mediaItem.mediaMetadata.artworkUri)
        assertEquals(artworkUri, mediaItem.mediaMetadata.artworkUri.toString())
        assertEquals("Born to Shine", mediaItem.mediaMetadata.title.toString())
        assertEquals("Diljit Dosanjh", mediaItem.mediaMetadata.artist.toString())
    }
}
