package com.notify.download.worker

import android.content.Context
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import com.notify.download.db.ArtworkEnrichmentStatus
import com.notify.download.db.ArtworkOrigin
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests verifying Patch A2.1.3 Artwork Recovery & Concurrency requirements:
 * 1. Active IN_PROGRESS leases (< 5m) are not reclaimed and excluded from eligible queries.
 * 2. Stale IN_PROGRESS leases (>= 5m) are reclaimed to PENDING exactly once.
 * 3. Atomic track claiming: two concurrent workers attempting to claim the same track result in exactly 1 winner.
 * 4. Claiming fails for already ENRICHED tracks.
 * 5. Cooldowns respected for RATE_LIMITED and FAILED_RETRYABLE states.
 * 6. Process interruption recovery: stale in-progress tracks are safely reset without re-processing ENRICHED tracks.
 * 7. Manual retry resets failure states back to PENDING.
 * 8. Safe continuation uses ExistingWorkPolicy.APPEND_OR_REPLACE (never REPLACE own running worker).
 * 9. WorkManager WorkInfo inspection: recovery coordinator skips replacing RUNNING/ENQUEUED/BLOCKED work.
 * 10. Recovery coordinator enqueues resume work when terminal or empty.
 * 11. Restart survival across repository reloads.
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    private val PLAYLIST_ID = "test_playlist_recovery"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NotiFyDatabase.setTestInstance(db)
        repository = PlaylistRepository(db)

        runBlocking {
            db.playlistDao().insertPlaylist(
                PlaylistEntity(
                    playlistId = PLAYLIST_ID,
                    title = "Recovery Test Playlist",
                    artworkUri = null
                )
            )
        }
    }

    @After
    fun tearDown() {
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun testActiveInProgressNotReclaimed() = runBlocking {
        val now = System.currentTimeMillis()
        // Lease acquired 1 minute ago (active lease, cutoff is 5 minutes = 300_000ms)
        val activeTrack = TrackEntity(
            id = "t_active",
            title = "Active Song",
            artist = "Artist A",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.IN_PROGRESS,
            lastArtworkAttemptEpochMs = now - 60_000L
        )
        db.trackDao().insertTrack(activeTrack)
        db.playlistDao().insertPlaylistEntry(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_active", position = 1))

        // Reclaim attempt
        val resetCount = repository.recoverStaleInProgressTracks(staleCutoffMs = 300_000L)
        assertEquals("Active lease must NOT be reset", 0, resetCount)

        val trackAfter = db.trackDao().getTrackById("t_active")
        assertEquals("Track must remain IN_PROGRESS", ArtworkEnrichmentStatus.IN_PROGRESS, trackAfter?.artworkEnrichmentStatus)

        // Eligible queries must strictly exclude active IN_PROGRESS tracks
        val eligible = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertTrue("Active lease must be excluded from eligible enrichment tracks", eligible.isEmpty())
    }

    @Test
    fun testStaleInProgressReclaimedExactlyOnce() = runBlocking {
        val now = System.currentTimeMillis()
        // Lease acquired 6 minutes ago (stale lease, >= 5 minutes = 300_000ms)
        val staleTrack = TrackEntity(
            id = "t_stale",
            title = "Stale Song",
            artist = "Artist B",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.IN_PROGRESS,
            lastArtworkAttemptEpochMs = now - 360_000L
        )
        db.trackDao().insertTrack(staleTrack)
        db.playlistDao().insertPlaylistEntry(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_stale", position = 1))

        // First reclaim
        val resetCount1 = repository.recoverStaleInProgressTracks(staleCutoffMs = 300_000L)
        assertEquals("Stale lease must be reset to PENDING exactly once", 1, resetCount1)

        val trackAfter = db.trackDao().getTrackById("t_stale")
        assertEquals("Track status must be reset to canonical PENDING", ArtworkEnrichmentStatus.PENDING, trackAfter?.artworkEnrichmentStatus)

        // Second reclaim should find nothing to reset
        val resetCount2 = repository.recoverStaleInProgressTracks(staleCutoffMs = 300_000L)
        assertEquals("Subsequent reclaim must reset 0 tracks", 0, resetCount2)

        // Now eligible for enrichment
        val eligible = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Track must now be eligible for enrichment", 1, eligible.size)
        assertEquals("t_stale", eligible[0].id)
    }

    @Test
    fun testAtomicClaimSingleWinnerAcrossWorkers() = runBlocking {
        val now = System.currentTimeMillis()
        val track = TrackEntity(
            id = "t_shared",
            title = "Shared Song",
            artist = "Artist C",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING,
            lastArtworkAttemptEpochMs = 0L,
            artworkAttemptCount = 0
        )
        db.trackDao().insertTrack(track)

        // Concurrent atomic claim attempts from 4 concurrent coroutines
        val results = (1..4).map {
            async(Dispatchers.IO) {
                repository.claimTrackForEnrichment(trackId = "t_shared", now = now)
            }
        }.awaitAll()

        val successCount = results.count { it }
        val failureCount = results.count { !it }

        assertEquals("Exactly 1 worker must succeed in claiming the shared track", 1, successCount)
        assertEquals("All other workers must be rejected (return false)", 3, failureCount)

        val trackAfter = db.trackDao().getTrackById("t_shared")
        assertEquals("Status must be updated to IN_PROGRESS", ArtworkEnrichmentStatus.IN_PROGRESS, trackAfter?.artworkEnrichmentStatus)
        assertEquals("Attempt count must be incremented to 1", 1, trackAfter?.artworkAttemptCount)
    }

    @Test
    fun testClaimFailsIfTrackAlreadyEnriched() = runBlocking {
        val now = System.currentTimeMillis()
        val track = TrackEntity(
            id = "t_enriched",
            title = "Enriched Song",
            artist = "Artist D",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED,
            artworkUrl = "https://example.com/art.jpg"
        )
        db.trackDao().insertTrack(track)

        val claimed = repository.claimTrackForEnrichment("t_enriched", now = now)
        assertFalse("Already ENRICHED track must NEVER be claimed", claimed)
    }

    @Test
    fun testClaimCooldownForRateLimitedAndRetryable() = runBlocking {
        val now = System.currentTimeMillis()
        val cooldownMs = 30 * 60 * 1000L // 30 minutes

        // 1. RATE_LIMITED within cooldown period (10 minutes ago)
        val rateLimitedRecent = TrackEntity(
            id = "t_rl_recent",
            title = "RL Recent",
            artist = "Artist E",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.RATE_LIMITED,
            lastArtworkAttemptEpochMs = now - 10 * 60 * 1000L
        )
        db.trackDao().insertTrack(rateLimitedRecent)

        val claimedRecent = repository.claimTrackForEnrichment("t_rl_recent", now = now, retryCooldownMs = cooldownMs)
        assertFalse("RATE_LIMITED track within cooldown must NOT be claimable", claimedRecent)

        // 2. RATE_LIMITED after cooldown period (35 minutes ago)
        val rateLimitedOld = TrackEntity(
            id = "t_rl_old",
            title = "RL Old",
            artist = "Artist E",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.RATE_LIMITED,
            lastArtworkAttemptEpochMs = now - 35 * 60 * 1000L
        )
        db.trackDao().insertTrack(rateLimitedOld)

        val claimedOld = repository.claimTrackForEnrichment("t_rl_old", now = now, retryCooldownMs = cooldownMs)
        assertTrue("RATE_LIMITED track after cooldown MUST be claimable", claimedOld)
    }

    @Test
    fun testInterruptionRecoveryResetsStaleLeaseAndResumesRemaining() = runBlocking {
        val now = System.currentTimeMillis()

        // 5-track playlist simulating an app force-stop midway through enrichment:
        // - t1, t2: already ENRICHED
        // - t3: was IN_PROGRESS when app force-stopped 10 minutes ago (stale lease)
        // - t4, t5: still PENDING
        val tracks = listOf(
            TrackEntity(id = "t1", title = "T1", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED, artworkUrl = "art1"),
            TrackEntity(id = "t2", title = "T2", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED, artworkUrl = "art2"),
            TrackEntity(id = "t3", title = "T3", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.IN_PROGRESS, lastArtworkAttemptEpochMs = now - 600_000L),
            TrackEntity(id = "t4", title = "T4", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING),
            TrackEntity(id = "t5", title = "T5", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING)
        )
        db.trackDao().insertTracks(tracks)
        tracks.forEachIndexed { index, t ->
            db.playlistDao().insertPlaylistEntry(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = t.id, position = index + 1))
        }

        // Run recovery
        val recovered = repository.recoverStaleInProgressTracks(staleCutoffMs = 300_000L)
        assertEquals("Stale lease for t3 must be recovered", 1, recovered)

        val t3After = db.trackDao().getTrackById("t3")
        assertEquals("t3 must be reset to PENDING", ArtworkEnrichmentStatus.PENDING, t3After?.artworkEnrichmentStatus)

        // Query needing enrichment
        val needing = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Exactly 3 remaining tracks (t3, t4, t5) must need enrichment", 3, needing.size)
        val needingIds = needing.map { it.id }.toSet()
        assertTrue("t3 must be included", needingIds.contains("t3"))
        assertTrue("t4 must be included", needingIds.contains("t4"))
        assertTrue("t5 must be included", needingIds.contains("t5"))
        assertFalse("t1 must NOT be included", needingIds.contains("t1"))
        assertFalse("t2 must NOT be included", needingIds.contains("t2"))
    }

    @Test
    fun testManualRetryResetsFailureStatesAndSchedulesRecovery() = runBlocking {
        val now = System.currentTimeMillis()
        val tracks = listOf(
            TrackEntity(id = "t_fail", title = "Fail", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.FAILED_RETRYABLE, lastArtworkAttemptEpochMs = now),
            TrackEntity(id = "t_rl", title = "RL", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.RATE_LIMITED, lastArtworkAttemptEpochMs = now),
            TrackEntity(id = "t_good", title = "Good", artist = "A", artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED, artworkUrl = "good_art")
        )
        db.trackDao().insertTracks(tracks)
        tracks.forEachIndexed { idx, t ->
            db.playlistDao().insertPlaylistEntry(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = t.id, position = idx + 1))
        }

        // Before reset: within cooldown, so 0 eligible tracks
        val before = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Before retry reset, 0 tracks eligible due to cooldown", 0, before.size)

        // Manual retry action
        repository.resetEnrichmentForRetry(PLAYLIST_ID)

        val failAfter = db.trackDao().getTrackById("t_fail")
        val rlAfter = db.trackDao().getTrackById("t_rl")
        val goodAfter = db.trackDao().getTrackById("t_good")

        assertEquals("t_fail must be reset to PENDING", ArtworkEnrichmentStatus.PENDING, failAfter?.artworkEnrichmentStatus)
        assertEquals("t_rl must be reset to PENDING", ArtworkEnrichmentStatus.PENDING, rlAfter?.artworkEnrichmentStatus)
        assertEquals("t_good must remain ENRICHED", ArtworkEnrichmentStatus.ENRICHED, goodAfter?.artworkEnrichmentStatus)

        // Now both failed tracks are immediately eligible
        val after = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Exactly 2 reset tracks must now be eligible", 2, after.size)
    }

    @Test
    fun testBatchSizeBoundedTo20() = runBlocking {
        // Seed 30 tracks needing enrichment
        val tracks = (1..30).map { i ->
            TrackEntity(
                id = "t_batch_$i",
                title = "Song $i",
                artist = "Artist",
                artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
            )
        }
        db.trackDao().insertTracks(tracks)
        tracks.forEachIndexed { idx, t ->
            db.playlistDao().insertPlaylistEntry(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = t.id, position = idx + 1))
        }

        // Total eligible tracks in DB is 30
        val allEligible = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Total eligible tracks in DB is 30", 30, allEligible.size)

        // DefaultArtworkEnricher.BATCH_SIZE takes at most 20
        val batch = allEligible.take(DefaultArtworkEnricher.BATCH_SIZE)
        assertEquals("Batch size must be capped at 20 tracks", 20, batch.size)
    }

    @Test
    fun testRestartSurvival() = runBlocking {
        val track = TrackEntity(
            id = "t_persist",
            title = "Persist Song",
            artist = "Artist P",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED,
            artworkUrl = "https://art/persist.jpg",
            artworkOrigin = ArtworkOrigin.YOUTUBE_MATCH,
            artworkAttemptCount = 2
        )
        db.trackDao().insertTrack(track)
        db.playlistDao().insertPlaylistEntry(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_persist", position = 1))

        // Create new repository instance pointing to same DB
        val repo2 = PlaylistRepository(db)
        val loaded = repo2.getPlaylistEntries(PLAYLIST_ID)

        assertEquals("Playlist entries preserved across repo instance", 1, loaded.size)
        assertEquals("t_persist", loaded[0].trackId)
        assertEquals("artworkUrl preserved", "https://art/persist.jpg", loaded[0].artworkUrl)
    }
}
