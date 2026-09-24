package com.notify.download

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.DownloadQueueStatus
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import com.notify.core.downloads.storage.AudioValidationResult
import com.notify.core.downloads.storage.AudioValidator
import com.notify.core.downloads.storage.OfflineStorage
import com.notify.core.model.DownloadBucket
import com.notify.download.db.AddTrackResult
import com.notify.download.db.DownloadState
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.engine.OfflineDownloadManager
import com.notify.download.engine.TrackDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowConnectivityManager
import java.io.File
import java.util.UUID

/**
 * Acceptance test suite validating the complete Duplicate Repair, Cross-Provider Uniqueness,
 * Safe Remove/Undo, Single-Job Concurrent Download, and Shared Continuous Download Observation requirements.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistDuplicationAndRemovalTest {

    private lateinit var app: Application
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository
    private lateinit var downloadManager: OfflineDownloadManager
    private lateinit var storage: OfflineStorage
    private lateinit var shadowCm: ShadowConnectivityManager

    private val PLAYLIST_ID = "pl_imported_punjabi"
    private val SPOTIFY_TRACK_ID = "spotify:born_to_shine_123"
    private val YOUTUBE_SOURCE_ID = "b2s_yt_vid_999"
    private val YOUTUBE_TRACK_ID = "youtube:$YOUTUBE_SOURCE_ID"

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Dispatchers.Unconfined.asExecutor())
            .setTransactionExecutor(Dispatchers.Unconfined.asExecutor())
            .build()
        NotiFyDatabase.setTestInstance(db)
        repository = PlaylistRepository(db, Dispatchers.Unconfined)
        downloadManager = OfflineDownloadManager(app)
        storage = downloadManager.getOfflineStorage()
        storage.ensureDirectoriesExist()

        val config = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app, config)

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowCm = Shadows.shadowOf(cm)

        TrackDownloadWorker.testAudioValidator = object : AudioValidator {
            override fun validateAudioFile(file: File): AudioValidationResult {
                return if (file.exists() && file.length() > 0) {
                    AudioValidationResult.Valid(durationMs = 210_000L, fileSizeBytes = file.length())
                } else {
                    AudioValidationResult.Invalid("File does not exist or empty")
                }
            }
        }

        runBlocking {
            db.playlistDao().insertPlaylist(
                PlaylistEntity(
                    playlistId = PLAYLIST_ID,
                    title = "Punjabi Hits",
                    sourceUrl = "https://open.spotify.com/playlist/37i9dQZF1DX4t95PaoR1k0"
                )
            )
        }
    }

    @After
    fun tearDown() {
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    /**
     * Requirement 1 & 4:
     * Repair existing Spotify/YouTube duplicates, then add the song again: still exactly one entry.
     */
    @Test
    fun testRepairDuplicates_thenAddAgain_maintainsSingleEntry() = runBlocking {
        // Setup: Playlist has Spotify track (pos 1) and YouTube track (pos 2)
        val now = System.currentTimeMillis()
        val spotifyTrack = TrackEntity(
            id = SPOTIFY_TRACK_ID,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            resolutionState = ResolutionState.MATCHED,
            downloadState = DownloadState.DOWNLOADED,
            dateAddedEpochMs = now - 1000
        )
        db.trackDao().insertTrack(spotifyTrack)

        val youtubeTrack = TrackEntity(
            id = YOUTUBE_TRACK_ID,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            resolutionState = ResolutionState.MATCHED,
            downloadState = DownloadState.NOT_DOWNLOADED,
            dateAddedEpochMs = now
        )
        db.trackDao().insertTrack(youtubeTrack)

        // Spotify track has confirmed selected match in track_sources
        val source = TrackSourceEntity(
            sourceKey = "$SPOTIFY_TRACK_ID:youtube:$YOUTUBE_SOURCE_ID",
            trackId = SPOTIFY_TRACK_ID,
            provider = "youtube",
            sourceId = YOUTUBE_SOURCE_ID,
            canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
            confidence = 1.0f,
            durationDeltaMs = 0L,
            selected = true
        )
        db.trackDao().insertSource(source)

        // Offline download entity exists for Spotify track
        val relKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "youtube", YOUTUBE_SOURCE_ID)
        val testFile = storage.resolveFile(relKey).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(1024) { 1 })
        }
        val offlineDownload = OfflineDownloadEntity(
            downloadId = UUID.randomUUID().toString(),
            trackId = SPOTIFY_TRACK_ID,
            provider = "youtube",
            providerSourceId = YOUTUBE_SOURCE_ID,
            canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
            relativeStorageKey = relKey,
            bucket = "PINNED",
            status = OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = testFile.length()
        )
        db.offlineDownloadDao().upsert(offlineDownload)

        // Add both to playlist_entries
        db.playlistDao().insertPlaylistEntryIgnore(
            PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = SPOTIFY_TRACK_ID, position = 1)
        )
        db.playlistDao().insertPlaylistEntryIgnore(
            PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = YOUTUBE_TRACK_ID, position = 2)
        )

        val entriesBefore = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals("Should start with 2 entries before repair", 2, entriesBefore.size)

        // Run repair
        val mergedCount = repository.repairDuplicatePlaylistEntries()
        assertEquals("Exactly 1 duplicate entry should be merged", 1, mergedCount)

        val entriesAfter = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals("Should have exactly 1 entry after repair", 1, entriesAfter.size)
        assertEquals("Retained entry should be the Spotify track", SPOTIFY_TRACK_ID, entriesAfter[0].trackId)
        assertEquals("Retained entry position must be 1", 1, entriesAfter[0].position)

        // Now attempt to add the same song again using YouTube video ID
        val addAgainResult = repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_ID,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            provider = "youtube",
            providerSourceId = YOUTUBE_SOURCE_ID
        )

        assertTrue(
            "Adding the song again must return AlreadyExists against the retained membership",
            addAgainResult is AddTrackResult.AlreadyExists
        )

        val entriesFinal = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals("Playlist must still contain exactly 1 entry", 1, entriesFinal.size)
        assertEquals(SPOTIFY_TRACK_ID, entriesFinal[0].trackId)
    }

    /**
     * Requirement 4:
     * Make repair idempotent: running it twice must make no further changes.
     */
    @Test
    fun testRepairDuplicatesIsIdempotent() = runBlocking {
        // Setup initial duplicate
        val now = System.currentTimeMillis()
        db.trackDao().insertTrack(TrackEntity(id = SPOTIFY_TRACK_ID, title = "Song", artist = "Artist", dateAddedEpochMs = now - 500))
        db.trackDao().insertTrack(TrackEntity(id = YOUTUBE_TRACK_ID, title = "Song", artist = "Artist", dateAddedEpochMs = now))
        db.trackDao().insertSource(
            TrackSourceEntity(
                sourceKey = "$SPOTIFY_TRACK_ID:youtube:$YOUTUBE_SOURCE_ID",
                trackId = SPOTIFY_TRACK_ID,
                provider = "youtube",
                sourceId = YOUTUBE_SOURCE_ID,
                canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
                confidence = 1.0f,
                durationDeltaMs = 0L,
                selected = true
            )
        )
        db.playlistDao().insertPlaylistEntryIgnore(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = SPOTIFY_TRACK_ID, position = 1))
        db.playlistDao().insertPlaylistEntryIgnore(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = YOUTUBE_TRACK_ID, position = 2))

        // First repair run
        val run1 = repository.repairDuplicatePlaylistEntries()
        assertEquals(1, run1)

        val entriesRun1 = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals(1, entriesRun1.size)
        assertEquals(1, entriesRun1[0].position)

        // Second repair run (must make 0 changes)
        val run2 = repository.repairDuplicatePlaylistEntries()
        assertEquals("Second repair run must merge 0 items", 0, run2)

        val entriesRun2 = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals(1, entriesRun2.size)
        assertEquals(1, entriesRun2[0].position)
        assertEquals(SPOTIFY_TRACK_ID, entriesRun2[0].trackId)
    }

    /**
     * Requirement 5:
     * An existing valid download must be reused.
     * Concurrent enqueue calls through Spotify and YouTube aliases must create only one job.
     */
    @Test
    fun testConcurrentEnqueueAcrossAliases_createsSingleJob() = runBlocking {
        val now = System.currentTimeMillis()
        db.trackDao().insertTrack(TrackEntity(id = SPOTIFY_TRACK_ID, title = "Song", artist = "Artist", dateAddedEpochMs = now))
        db.trackDao().insertTrack(TrackEntity(id = YOUTUBE_TRACK_ID, title = "Song", artist = "Artist", dateAddedEpochMs = now))
        db.trackDao().insertSource(
            TrackSourceEntity(
                sourceKey = "$SPOTIFY_TRACK_ID:youtube:$YOUTUBE_SOURCE_ID",
                trackId = SPOTIFY_TRACK_ID,
                provider = "youtube",
                sourceId = YOUTUBE_SOURCE_ID,
                canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
                confidence = 1.0f,
                durationDeltaMs = 0L,
                selected = true
            )
        )

        // Launch concurrent download enqueue calls for Spotify alias and YouTube alias
        val def1 = async(Dispatchers.IO) {
            downloadManager.enqueueTrackDownload(SPOTIFY_TRACK_ID, bucket = DownloadBucket.PINNED)
        }
        val def2 = async(Dispatchers.IO) {
            downloadManager.enqueueTrackDownload(YOUTUBE_TRACK_ID, bucket = DownloadBucket.PINNED)
        }

        val results = listOf(def1.await(), def2.await())
        val allQueueItems = db.downloadQueueDao().observeAllQueue().first()
        assertEquals(
            "Concurrent enqueue through Spotify and YouTube aliases must result in at most one queue item",
            1,
            allQueueItems.size
        )
    }

    /**
     * Requirement 6:
     * Make Remove and Undo safe:
     * Delete using both playlistId and entryId.
     * Remove only the membership; preserve the audio and other playlists.
     * Undo must recheck membership. If the song was already re-added, return AlreadyExists instead of creating a duplicate.
     */
    @Test
    fun testRemove_thenManualReAdd_thenUndo_maintainsSingleEntry() = runBlocking {
        val track = TrackEntity(id = SPOTIFY_TRACK_ID, title = "Born to Shine", artist = "Diljit Dosanjh")
        db.trackDao().insertTrack(track)
        db.trackDao().insertSource(
            TrackSourceEntity(
                sourceKey = "$SPOTIFY_TRACK_ID:youtube:$YOUTUBE_SOURCE_ID",
                trackId = SPOTIFY_TRACK_ID,
                provider = "youtube",
                sourceId = YOUTUBE_SOURCE_ID,
                canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
                confidence = 1.0f,
                durationDeltaMs = 0L,
                selected = true
            )
        )

        // Add to playlist
        val initialEntry = repository.addTrackToPlaylist(
            playlistId = PLAYLIST_ID,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            provider = "youtube",
            providerSourceId = YOUTUBE_SOURCE_ID
        )

        val entries1 = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals(1, entries1.size)
        val entryId = entries1[0].entryId

        // Step 1: Remove track from playlist
        val removedEntry = repository.removeTrackFromPlaylist(PLAYLIST_ID, entryId)
        assertNotNull("Removed entry must not be null", removedEntry)

        val entriesAfterRemove = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals("Playlist must be empty after remove", 0, entriesAfterRemove.size)

        // Check that parent TrackEntity in tracks table is preserved
        val retainedTrack = db.trackDao().getTrackById(SPOTIFY_TRACK_ID)
        assertNotNull("Parent TrackEntity must NOT be deleted", retainedTrack)

        // Step 2: Manually re-add the track
        val reAddResult = repository.addTrackToPlaylistIfAbsent(
            playlistId = PLAYLIST_ID,
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            provider = "youtube",
            providerSourceId = YOUTUBE_SOURCE_ID
        )
        assertTrue("Manual re-add must succeed", reAddResult is AddTrackResult.Added)

        val entriesAfterReAdd = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals("Playlist has 1 entry after re-add", 1, entriesAfterReAdd.size)

        // Step 3: Undo the original removal
        val undoResult = repository.undoRemoveTrackFromPlaylist(removedEntry!!)
        assertEquals(
            "Undo must recheck membership and return AlreadyExists when already present",
            AddTrackResult.AlreadyExists,
            undoResult
        )

        // Verify still exactly 1 entry
        val entriesFinal = db.playlistDao().getPlaylistEntries(PLAYLIST_ID)
        assertEquals("Playlist must still contain exactly 1 entry after Undo", 1, entriesFinal.size)
        assertEquals(1, entriesFinal[0].position)
    }

    /**
     * Requirement 5:
     * Observe shared download state continuously.
     * Room Flow emits changes across aliases when download is completed or removed.
     */
    @Test
    fun testContinuousDownloadStateObservationAcrossAliases() = runBlocking {
        db.trackDao().insertTrack(TrackEntity(id = SPOTIFY_TRACK_ID, title = "Song", artist = "Artist"))
        db.trackDao().insertSource(
            TrackSourceEntity(
                sourceKey = "$SPOTIFY_TRACK_ID:youtube:$YOUTUBE_SOURCE_ID",
                trackId = SPOTIFY_TRACK_ID,
                provider = "youtube",
                sourceId = YOUTUBE_SOURCE_ID,
                canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
                confidence = 1.0f,
                durationDeltaMs = 0L,
                selected = true
            )
        )

        // Before download: emits false
        val beforeSpotify = downloadManager.observeIsRecordingDownloaded(SPOTIFY_TRACK_ID, YOUTUBE_SOURCE_ID).first()
        val beforeYoutube = downloadManager.observeIsRecordingDownloaded(YOUTUBE_TRACK_ID, YOUTUBE_SOURCE_ID).first()
        assertFalse(beforeSpotify)
        assertFalse(beforeYoutube)

        // Complete download for Spotify track
        val relKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "youtube", YOUTUBE_SOURCE_ID)
        val testFile = storage.resolveFile(relKey).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(512) { 1 })
        }
        val dl = OfflineDownloadEntity(
            downloadId = UUID.randomUUID().toString(),
            trackId = SPOTIFY_TRACK_ID,
            provider = "youtube",
            providerSourceId = YOUTUBE_SOURCE_ID,
            canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
            relativeStorageKey = relKey,
            bucket = "PINNED",
            status = OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = testFile.length()
        )
        db.offlineDownloadDao().upsert(dl)

        // Now observe flow for both aliases: both must emit true!
        val afterSpotify = downloadManager.observeIsRecordingDownloaded(SPOTIFY_TRACK_ID, YOUTUBE_SOURCE_ID).first()
        val afterYoutube = downloadManager.observeIsRecordingDownloaded(YOUTUBE_TRACK_ID, YOUTUBE_SOURCE_ID).first()
        assertTrue("Spotify alias must observe download as true", afterSpotify)
        assertTrue("YouTube alias must observe download as true", afterYoutube)

        // Remove download
        downloadManager.removeDownloadForTrack(SPOTIFY_TRACK_ID)

        // Observe flow after removal: both must emit false!
        val removedSpotify = downloadManager.observeIsRecordingDownloaded(SPOTIFY_TRACK_ID, YOUTUBE_SOURCE_ID).first()
        val removedYoutube = downloadManager.observeIsRecordingDownloaded(YOUTUBE_TRACK_ID, YOUTUBE_SOURCE_ID).first()
        assertFalse("Spotify alias must observe download as false after removal", removedSpotify)
        assertFalse("YouTube alias must observe download as false after removal", removedYoutube)
    }

    /**
     * Requirement 5 & 6:
     * Existing downloaded audio remains playable in airplane mode.
     */
    @Test
    fun testExistingDownloadedAudioRemainsPlayableInAirplaneMode() = runBlocking {
        // Setup downloaded audio file
        val relKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "youtube", YOUTUBE_SOURCE_ID)
        val audioFile = storage.resolveFile(relKey).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048) { 42 })
        }
        val dl = OfflineDownloadEntity(
            downloadId = UUID.randomUUID().toString(),
            trackId = SPOTIFY_TRACK_ID,
            provider = "youtube",
            providerSourceId = YOUTUBE_SOURCE_ID,
            canonicalUrl = "https://www.youtube.com/watch?v=$YOUTUBE_SOURCE_ID",
            relativeStorageKey = relKey,
            bucket = "PINNED",
            status = OfflineDownloadStatus.COMPLETED,
            fileSizeBytes = audioFile.length()
        )
        db.offlineDownloadDao().upsert(dl)

        // Set connectivity to disconnected / airplane mode
        shadowCm.setActiveNetworkInfo(null)

        // Look up offline playback URI by YouTube videoId (from online search or Now Playing)
        val offlineUriFromSource = downloadManager.getOfflinePlaybackUriForSource(YOUTUBE_SOURCE_ID)
        assertNotNull("Must return offline content URI in airplane mode by sourceId", offlineUriFromSource)
        assertTrue(
            "URI must point to offline content provider or local file",
            offlineUriFromSource.toString().contains("com.notify.offline") || offlineUriFromSource?.scheme == "file"
        )

        // Look up offline playback URI by catalog trackId
        val offlineUriFromTrack = downloadManager.getOfflinePlaybackUri(SPOTIFY_TRACK_ID)
        assertNotNull("Must return offline content URI in airplane mode by trackId", offlineUriFromTrack)

        // Verify physical file is present and readable on disk
        val resolvedFile = storage.resolveFile(relKey)
        assertTrue("Resolved audio file must physically exist", resolvedFile.exists())
        assertEquals(2048L, resolvedFile.length())
    }
}
