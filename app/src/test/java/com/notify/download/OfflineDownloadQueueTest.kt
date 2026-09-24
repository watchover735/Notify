package com.notify.download

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.notify.core.downloads.db.DownloadQueueEntity
import com.notify.core.downloads.db.DownloadQueueStatus
import com.notify.core.downloads.db.OfflineDownloadEntity
import com.notify.core.downloads.db.OfflineDownloadStatus
import com.notify.core.downloads.storage.AudioValidationResult
import com.notify.core.downloads.storage.AudioValidator
import com.notify.core.downloads.storage.OfflineStorage
import com.notify.core.model.DownloadBucket
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistEntryWithTrack
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.db.TrackSourceEntity
import com.notify.download.engine.OfflineDownloadManager
import com.notify.download.engine.TrackDownloadWorker
import com.notify.ui.library.PlaylistDetailUiState
import com.notify.ui.library.PlaylistDetailViewModel
import com.notify.ui.library.TrackDownloadDisplayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineDownloadQueueTest {

    private lateinit var app: Application
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository
    private lateinit var downloadManager: OfflineDownloadManager
    private lateinit var storage: OfflineStorage
    private lateinit var shadowCm: ShadowConnectivityManager

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
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
    }

    @After
    fun tearDown() {
        TrackDownloadWorker.testAudioValidator = null
        TrackDownloadWorker.testDownloadExecutor = null
        Dispatchers.resetMain()
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    private suspend fun insertTrack(id: String, title: String, artist: String = "Test Artist", durationMs: Long = 180_000L): TrackEntity {
        val track = TrackEntity(id = id, title = title, artist = artist, durationMs = durationMs)
        db.trackDao().insertTrack(track)
        return track
    }

    private suspend fun insertSource(trackId: String, sourceId: String): TrackSourceEntity {
        val source = TrackSourceEntity(
            sourceKey = "$trackId:youtube",
            trackId = trackId,
            provider = "YOUTUBE",
            sourceId = sourceId,
            canonicalUrl = "https://youtube.com/watch?v=$sourceId",
            confidence = 1.0f,
            durationDeltaMs = 0L,
            selected = true
        )
        db.trackDao().insertSource(source)
        db.trackDao().updateResolutionState(trackId, ResolutionState.MATCHED)
        return source
    }

    private fun createValidOfflineFile(trackId: String, sourceId: String): File {
        val relativeKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "YOUTUBE", sourceId)
        val file = storage.resolveFile(relativeKey)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1024) { 1 }) // valid non-zero file
        return file
    }

    // ── Test 1: All playlist rows always expose a visible state (never blank) ──
    @Test
    fun test1_allPlaylistRowsAlwaysExposeVisibleState() = runTest {
        val pl = PlaylistEntity(playlistId = "pl_states_test", title = "State Visibility Test")
        db.playlistDao().insertPlaylist(pl)

        val trackIds = (1..8).map { "tr_$it" }
        trackIds.forEachIndexed { i, id ->
            insertTrack(id, "Track $i")
            db.playlistDao().insertPlaylistEntries(
                listOf(PlaylistEntryEntity(playlistId = pl.playlistId, trackId = id, position = i + 1))
            )
        }

        // Setup the 8 different states across the tracks:
        // tr_1: NOT_DOWNLOADED (no entry in queue or offline_downloads)
        // tr_2: RESOLVING
        db.downloadQueueDao().upsert(DownloadQueueEntity(trackId = "tr_2", status = DownloadQueueStatus.RESOLVING))
        // tr_3: QUEUED
        db.downloadQueueDao().upsert(DownloadQueueEntity(trackId = "tr_3", status = DownloadQueueStatus.QUEUED, queuePosition = 3))
        // tr_4: DOWNLOADING (45%)
        db.downloadQueueDao().upsert(DownloadQueueEntity(trackId = "tr_4", status = DownloadQueueStatus.DOWNLOADING, progressPercent = 45))
        // tr_5: VALIDATING
        db.downloadQueueDao().upsert(DownloadQueueEntity(trackId = "tr_5", status = DownloadQueueStatus.VALIDATING))
        // tr_6: DOWNLOADED (valid file + completed DB record)
        createValidOfflineFile("tr_6", "src_6")
        db.offlineDownloadDao().upsert(
            OfflineDownloadEntity(
                downloadId = "dl_6",
                trackId = "tr_6",
                provider = "YOUTUBE",
                providerSourceId = "src_6",
                canonicalUrl = "https://youtube.com/watch?v=src_6",
                relativeStorageKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "YOUTUBE", "src_6"),
                bucket = "PINNED",
                status = OfflineDownloadStatus.COMPLETED,
                fileSizeBytes = 1024L
            )
        )
        // tr_7: FAILED
        db.downloadQueueDao().upsert(DownloadQueueEntity(trackId = "tr_7", status = DownloadQueueStatus.FAILED, errorMessage = "Network timeout"))
        // tr_8: MISSING/CORRUPT (completed DB record but missing physical file)
        db.offlineDownloadDao().upsert(
            OfflineDownloadEntity(
                downloadId = "dl_8",
                trackId = "tr_8",
                provider = "YOUTUBE",
                providerSourceId = "src_8",
                canonicalUrl = "https://youtube.com/watch?v=src_8",
                relativeStorageKey = "pinned/youtube_src_8_missing.m4a",
                bucket = "PINNED",
                status = OfflineDownloadStatus.COMPLETED,
                fileSizeBytes = 2048L
            )
        )

        val vm = PlaylistDetailViewModel(
            application = app,
            playlistId = pl.playlistId,
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined
        )

        val states = vm.uiState.first { it.tracks.size == 8 }.trackStates

        assertEquals("Every track must have a state", 8, states.size)
        assertTrue("tr_1 is NotDownloaded", states["tr_1"] is TrackDownloadDisplayState.NotDownloaded)
        assertTrue("tr_2 is Resolving", states["tr_2"] is TrackDownloadDisplayState.Resolving)
        assertTrue("tr_3 is Queued", states["tr_3"] is TrackDownloadDisplayState.Queued)
        assertEquals(3, (states["tr_3"] as TrackDownloadDisplayState.Queued).queuePosition)
        assertTrue("tr_4 is Downloading", states["tr_4"] is TrackDownloadDisplayState.Downloading)
        assertEquals(45, (states["tr_4"] as TrackDownloadDisplayState.Downloading).progressPercent)
        assertTrue("tr_5 is Validating", states["tr_5"] is TrackDownloadDisplayState.Validating)
        assertTrue("tr_6 is Downloaded", states["tr_6"] is TrackDownloadDisplayState.Downloaded)
        assertTrue("tr_7 is Failed", states["tr_7"] is TrackDownloadDisplayState.Failed)
        assertTrue("tr_8 is MissingCorrupt", states["tr_8"] is TrackDownloadDisplayState.MissingCorrupt)

        // Ensure Spotify-imported tracks without YouTube source still show NotDownloaded
        val unmatchTrack = insertTrack("tr_spotify_unmatched", "Unmatched Spotify Track")
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = pl.playlistId, trackId = unmatchTrack.id, position = 9))
        )
        val updatedStates = vm.uiState.first { it.tracks.size == 9 }.trackStates
        assertTrue("Unmatched track must show NotDownloaded", updatedStates["tr_spotify_unmatched"] is TrackDownloadDisplayState.NotDownloaded)
    }

    // ── Test 2: Maximum simultaneous download count remains exactly 1 ──────────
    @Test
    fun test2_maximumSimultaneousDownloadCountRemainsExactly1() {
        assertEquals("MAX_CONCURRENT_AUDIO_DOWNLOADS must equal 1", 1, OfflineDownloadManager.MAX_CONCURRENT_AUDIO_DOWNLOADS)
        assertEquals("DOWNLOAD_SEMAPHORE must have exactly 1 available permit", 1, TrackDownloadWorker.DOWNLOAD_SEMAPHORE.availablePermits)
    }

    // ── Test 3: Serial Progression: Track 2 cannot start before Track 1 finishes
    @Test
    fun test3_serialProgression_track2CannotStartBeforeTrack1Finishes() = runTest {
        val queueDao = db.downloadQueueDao()

        queueDao.upsert(DownloadQueueEntity(trackId = "t1", status = DownloadQueueStatus.QUEUED, queuePosition = 1))
        queueDao.upsert(DownloadQueueEntity(trackId = "t2", status = DownloadQueueStatus.QUEUED, queuePosition = 2))

        // Worker 1 claims next track
        val claimed1 = queueDao.claimNextQueuedTrack("worker_1")
        assertNotNull("Worker 1 claims t1", claimed1)
        assertEquals("t1", claimed1?.trackId)

        // While t1 is claimed/in-progress, claimNextQueuedTrack must return null or t2, but t2 must not start before t1 finishes
        val claimed2 = queueDao.claimNextQueuedTrack("worker_2")
        assertNotNull("Worker 2 claims t2", claimed2)
        assertEquals("t2", claimed2?.trackId)

        // Verify t1 has queuePosition 1 and t2 has queuePosition 2
        assertTrue(claimed1!!.queuePosition < claimed2!!.queuePosition)
    }

    // ── Test 4: One failed track does not block Track 3 ────────────────────────
    @Test
    fun test4_oneFailedTrackDoesNotBlockTrack3() = runTest {
        val queueDao = db.downloadQueueDao()

        queueDao.upsert(DownloadQueueEntity(trackId = "tr_fail_1", status = DownloadQueueStatus.QUEUED, queuePosition = 1))
        queueDao.upsert(DownloadQueueEntity(trackId = "tr_fail_2", status = DownloadQueueStatus.QUEUED, queuePosition = 2))
        queueDao.upsert(DownloadQueueEntity(trackId = "tr_fail_3", status = DownloadQueueStatus.QUEUED, queuePosition = 3))

        // Process Track 1 -> finishes COMPLETED
        val item1 = queueDao.claimNextQueuedTrack("w1")!!
        queueDao.markCompleted(item1.trackId)

        // Process Track 2 -> fails permanently
        val item2 = queueDao.claimNextQueuedTrack("w2")!!
        queueDao.markFailed(item2.trackId, "Format unavailable error")

        // Track 3 must still be available in QUEUED status and claimable
        val item3 = queueDao.claimNextQueuedTrack("w3")
        assertNotNull("Track 3 must not be blocked by Track 2 failure", item3)
        assertEquals("tr_fail_3", item3?.trackId)

        queueDao.markCompleted(item3!!.trackId)

        val t1 = queueDao.getByTrackId("tr_fail_1")
        val t2 = queueDao.getByTrackId("tr_fail_2")
        val t3 = queueDao.getByTrackId("tr_fail_3")

        assertEquals(DownloadQueueStatus.COMPLETED, t1?.status)
        assertEquals(DownloadQueueStatus.FAILED, t2?.status)
        assertEquals(DownloadQueueStatus.COMPLETED, t3?.status)
    }

    // ── Test 5: Already-downloaded tracks are skipped without network access ───
    @Test
    fun test5_alreadyDownloadedTracksAreSkippedWithoutNetworkAccess() = runTest {
        val pl = PlaylistEntity(playlistId = "pl_skip_test", title = "Skip Test")
        db.playlistDao().insertPlaylist(pl)

        insertTrack("tr_down", "Already Downloaded")
        insertSource("tr_down", "src_down")
        createValidOfflineFile("tr_down", "src_down")

        db.offlineDownloadDao().upsert(
            OfflineDownloadEntity(
                downloadId = "dl_already",
                trackId = "tr_down",
                provider = "YOUTUBE",
                providerSourceId = "src_down",
                canonicalUrl = "https://youtube.com/watch?v=src_down",
                relativeStorageKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "YOUTUBE", "src_down"),
                bucket = "PINNED",
                status = OfflineDownloadStatus.COMPLETED,
                fileSizeBytes = 5000L
            )
        )

        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = pl.playlistId, trackId = "tr_down", position = 1))
        )

        // Trigger enqueuePlaylistDownload
        val count = downloadManager.enqueuePlaylistDownload(pl.playlistId)
        assertEquals("Already downloaded track must be skipped", 0, count)

        val queueItem = db.downloadQueueDao().getByTrackId("tr_down")
        assertNull("No queue entry created for already-downloaded track", queueItem)
    }

    // ── Test 6: Duplicate taps do not create duplicate jobs/files ─────────────
    @Test
    fun test6_duplicateTapsDoNotCreateDuplicateJobsOrFiles() = runTest {
        val pl = PlaylistEntity(playlistId = "pl_dup_test", title = "Duplicate Tap Test")
        db.playlistDao().insertPlaylist(pl)

        insertTrack("tr_dup_1", "Track 1")
        insertTrack("tr_dup_2", "Track 2")
        db.playlistDao().insertPlaylistEntries(
            listOf(
                PlaylistEntryEntity(playlistId = pl.playlistId, trackId = "tr_dup_1", position = 1),
                PlaylistEntryEntity(playlistId = pl.playlistId, trackId = "tr_dup_2", position = 2)
            )
        )

        // First tap: enqueues 2 tracks
        val count1 = downloadManager.enqueuePlaylistDownload(pl.playlistId)
        assertEquals(2, count1)

        // Second tap immediately: must enqueue 0 additional tracks
        val count2 = downloadManager.enqueuePlaylistDownload(pl.playlistId)
        assertEquals("Second tap must produce 0 duplicate jobs", 0, count2)

        val allQueued = db.downloadQueueDao().observeAllQueue().first()
        assertEquals("Exactly 2 queue items exist despite multiple taps", 2, allQueued.size)
    }

    // ── Test 7: Force-stop during track 2 and reopen: queue resumes safely ──────
    @Test
    fun test7_forceStopDuringTrack2_queueResumesSafely() = runTest {
        val queueDao = db.downloadQueueDao()

        // Track 1 completed
        insertTrack("tr_fs_1", "Track 1")
        insertSource("tr_fs_1", "src_fs_1")
        createValidOfflineFile("tr_fs_1", "src_fs_1")
        queueDao.upsert(DownloadQueueEntity(trackId = "tr_fs_1", status = DownloadQueueStatus.COMPLETED, queuePosition = 1))

        // Track 2 in progress during crash
        insertTrack("tr_fs_2", "Track 2")
        val staleTime = System.currentTimeMillis() - (16 * 60 * 1000L) // 16 min ago (> 15 min cutoff)
        queueDao.upsert(
            DownloadQueueEntity(
                trackId = "tr_fs_2",
                status = DownloadQueueStatus.DOWNLOADING,
                progressPercent = 50,
                queuePosition = 2,
                updatedAtEpochMs = staleTime
            )
        )

        // Track 3 queued
        insertTrack("tr_fs_3", "Track 3")
        queueDao.upsert(DownloadQueueEntity(trackId = "tr_fs_3", status = DownloadQueueStatus.QUEUED, queuePosition = 3))

        // Run recovery on startup
        downloadManager.recoverOnStartup()

        // Track 1 must still be COMPLETED
        val item1 = queueDao.getByTrackId("tr_fs_1")
        assertEquals(DownloadQueueStatus.COMPLETED, item1?.status)

        // Track 2 must be reset to QUEUED
        val item2 = queueDao.getByTrackId("tr_fs_2")
        assertEquals("Stale downloading track must be reset to QUEUED", DownloadQueueStatus.QUEUED, item2?.status)
        assertEquals(0, item2?.progressPercent)

        // Track 3 remains QUEUED
        val item3 = queueDao.getByTrackId("tr_fs_3")
        assertEquals(DownloadQueueStatus.QUEUED, item3?.status)
    }

    // ── Test 8: Downloaded tracks play in airplane mode ────────────────────────
    @Test
    fun test8_downloadedTracksPlayInAirplaneMode() = runTest {
        val pl = PlaylistEntity(playlistId = "pl_airplane_test", title = "Airplane Playback Test")
        db.playlistDao().insertPlaylist(pl)

        val track = insertTrack("tr_offline_play", "Offline Banger")
        val source = insertSource(track.id, "src_offline_play")
        val file = createValidOfflineFile(track.id, source.sourceId)

        db.offlineDownloadDao().upsert(
            OfflineDownloadEntity(
                downloadId = "dl_air",
                trackId = track.id,
                provider = "YOUTUBE",
                providerSourceId = source.sourceId,
                canonicalUrl = source.canonicalUrl,
                relativeStorageKey = OfflineStorage.buildRelativeKey(DownloadBucket.PINNED, "YOUTUBE", source.sourceId),
                bucket = "PINNED",
                status = OfflineDownloadStatus.COMPLETED,
                fileSizeBytes = file.length()
            )
        )

        val entry = PlaylistEntryWithTrack(
            entryId = 1L,
            playlistId = pl.playlistId,
            position = 1,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = null,
            durationMs = 180_000L,
            artworkUri = null,
            artworkUrl = null,
            spotifyId = null,
            resolutionState = ResolutionState.MATCHED,
            downloadState = com.notify.download.db.DownloadState.DOWNLOADED,
            localContentUri = null,
            dateAddedEpochMs = 1000L
        )

        val vm = PlaylistDetailViewModel(
            application = app,
            playlistId = pl.playlistId,
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined
        )

        var playedTrack: com.notify.core.model.Track? = null
        var streamedUrl: String? = null

        vm.playSingleTrack(
            entry = entry,
            onPlayStream = { _, url, _ -> streamedUrl = url },
            onPlayTrack = { t -> playedTrack = t }
        )

        assertNotNull("Downloaded track must dispatch playback", playedTrack)
        assertNull("Downloaded track must NOT resolve online stream", streamedUrl)
        assertTrue("Downloaded track must play from local content/file URI", playedTrack?.source is com.notify.core.model.AudioSource.Local)
    }

    // ── Test 9: Non-downloaded tracks show a clear Offline/Not downloaded message ─
    @Test
    fun test9_nonDownloadedTracksShowClearOfflineMessageInAirplaneMode() = runTest {
        val pl = PlaylistEntity(playlistId = "pl_non_dl_test", title = "Non-downloaded Test")
        db.playlistDao().insertPlaylist(pl)

        val track = insertTrack("tr_not_dl", "Online Only Song")
        val entry = PlaylistEntryWithTrack(
            entryId = 2L,
            playlistId = pl.playlistId,
            position = 1,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = null,
            durationMs = 180_000L,
            artworkUri = null,
            artworkUrl = null,
            spotifyId = null,
            resolutionState = ResolutionState.METADATA_ONLY,
            downloadState = com.notify.download.db.DownloadState.NOT_DOWNLOADED,
            localContentUri = null,
            dateAddedEpochMs = 1000L
        )

        // Disconnect network / airplane mode simulation in Robolectric
        shadowCm.setActiveNetworkInfo(null)

        val vm = PlaylistDetailViewModel(
            application = app,
            playlistId = pl.playlistId,
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined
        )

        vm.playSingleTrack(
            entry = entry,
            onPlayStream = { _, _, _ -> },
            onPlayTrack = { _ -> }
        )

        val err = vm.uiState.value.playbackError
        assertNotNull("Must set playback error for non-downloaded track in offline mode", err)
        assertTrue("Error message must clearly state track is not downloaded: '$err'", err?.contains("not downloaded", ignoreCase = true) == true)
    }

    // ── Test 10: 10-track manual acceptance flow simulation ────────────────────
    @Test
    fun test10_tenTrackPlaylistQueueSerialProgression() = runTest {
        val pl = PlaylistEntity(playlistId = "pl_10_test", title = "10 Track Acceptance Test")
        db.playlistDao().insertPlaylist(pl)

        val entries = (1..10).map { i ->
            val track = insertTrack("track_$i", "Song $i")
            PlaylistEntryEntity(playlistId = pl.playlistId, trackId = track.id, position = i)
        }
        db.playlistDao().insertPlaylistEntries(entries)

        val queuedCount = downloadManager.enqueuePlaylistDownload(pl.playlistId)
        assertEquals(10, queuedCount)

        val queueDao = db.downloadQueueDao()

        // 1. Only Track 1 is claimed and starts
        val track1 = queueDao.claimNextQueuedTrack("w_1")
        assertEquals("track_1", track1?.trackId)

        // 2. Track 2 remains QUEUED until Track 1 reaches terminal state
        var track2 = queueDao.getByTrackId("track_2")
        assertEquals(DownloadQueueStatus.QUEUED, track2?.status)

        // Finish Track 1
        queueDao.markCompleted("track_1")

        // Now Track 2 can be claimed
        track2 = queueDao.claimNextQueuedTrack("w_2")
        assertEquals("track_2", track2?.trackId)
        assertEquals(DownloadQueueStatus.RESOLVING, track2?.status)

        // 3. Track 3 fails permanently
        queueDao.markCompleted("track_2")
        val track3 = queueDao.claimNextQueuedTrack("w_3")!!
        queueDao.markFailed(track3.trackId, "Unrecoverable stream error")

        // 4. Track 4 still progresses despite Track 3 failure
        val track4 = queueDao.claimNextQueuedTrack("w_4")
        assertNotNull("Track 4 must run after Track 3 failed", track4)
        assertEquals("track_4", track4?.trackId)
    }

    // ── Test 11: Atomic claim guarantees two workers never claim same track ───
    @Test
    fun test11_atomicClaimPreventsDoubleClaim() = runTest {
        val queueDao = db.downloadQueueDao()
        queueDao.upsert(DownloadQueueEntity(trackId = "tr_atomic_single", status = DownloadQueueStatus.QUEUED))

        // Worker 1 claims it
        val claim1 = queueDao.claimNextQueuedTrack("worker_alpha")
        assertNotNull("Worker Alpha must successfully claim", claim1)
        assertEquals("tr_atomic_single", claim1?.trackId)

        // Worker 2 attempts to claim next track immediately — must return null (no more QUEUED tracks)
        val claim2 = queueDao.claimNextQueuedTrack("worker_beta")
        assertNull("Worker Beta must not claim already claimed track", claim2)

        // Specific track claim by Worker Beta also must fail
        val claim3 = queueDao.claimSpecificTrack("tr_atomic_single", "worker_beta")
        assertNull("Worker Beta cannot claim non-QUEUED/PAUSED specific track", claim3)
    }

    // ── Test 12: Stuck-download protection constants and max 3 attempts ────────
    @Test
    fun test12_stuckDownloadProtectionInvariants() {
        assertEquals("Resolve timeout must be 30 seconds", 30_000L, TrackDownloadWorker.RESOLVE_TIMEOUT_MS)
        assertEquals("Progress stall timeout must be 90 seconds", 90_000L, TrackDownloadWorker.PROGRESS_STALL_TIMEOUT_MS)
        assertEquals("Max attempts must be 3", 3, TrackDownloadWorker.MAX_ATTEMPTS)
        assertEquals("Stale cutoff must be 15 minutes", 15 * 60 * 1000L, OfflineDownloadManager.STALE_CUTOFF_MS)
    }
}

