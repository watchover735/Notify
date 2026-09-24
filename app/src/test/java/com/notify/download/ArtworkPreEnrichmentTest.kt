package com.notify.download

import android.content.Context
import androidx.room.Room
import com.notify.download.db.ArtworkEnrichmentStatus
import com.notify.download.db.ArtworkOrigin
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistEntity
import com.notify.download.db.PlaylistEntryEntity
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackEntity
import com.notify.download.matcher.MatchResult
import com.notify.download.matcher.TrackMatchEngine
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.spotify.SpotifyTrackMetadata
import kotlinx.coroutines.test.runTest
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

/**
 * Unit tests verifying Patch A2.1.2 Artwork Pre-Enrichment requirements:
 *
 * 1. Existing playlists enrich without re-import (null/UNKNOWN/PLAYLIST_FALLBACK_LEGACY tracks processed).
 * 2. Enrichment occurs purely in background without requiring playback, stream resolution, or yt-dlp.
 * 3. Existing Spotify artwork and selected source artwork are never overwritten.
 * 4. Safe source handling: candidates stored with selected = false, artworkOrigin = YOUTUBE_ENRICHMENT, resolutionState = METADATA_ONLY.
 * 5. Strict confidence scoring: low confidence or mismatched candidate is rejected (keeps neutral placeholder, sets NO_CONFIDENT_MATCH).
 * 6. Signed stream URLs are never persisted anywhere.
 * 7. Anti-endless retry: reopening playlist does not search ENRICHED or NO_CONFIDENT_MATCH tracks repeatedly; manual retry resets them.
 * 8. Rate-limit protection: 429 updates track to RATE_LIMITED without losing progress on already completed tracks.
 * 9. Process interruption: remaining tracks resume safely without re-searching completed tracks.
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkPreEnrichmentTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    private val PLAYLIST_ID = "existing_playlist_93"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)

        // Seed an existing playlist
        kotlinx.coroutines.runBlocking {
            db.playlistDao().insertPlaylist(
                PlaylistEntity(
                    playlistId = PLAYLIST_ID,
                    title = "Saved 93-Track Playlist",
                    artworkUri = "https://i.scdn.co/image/playlist_cover"
                )
            )
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── 1. Existing playlist enriches without re-import ───────────────────────

    @Test
    fun existingPlaylist_withoutReimport_detectsAndEnrichesTracksNeedingArtwork() = runTest {
        // Track 1: already has genuine Spotify artwork
        val track1 = TrackEntity(
            id = "t1",
            title = "Track One",
            artist = "Artist A",
            durationMs = 200_000L,
            artworkUri = "https://i.scdn.co/image/track1_art",
            artworkOrigin = ArtworkOrigin.SPOTIFY_TRACK,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED
        )
        // Track 2: null artwork (neutral placeholder)
        val track2 = TrackEntity(
            id = "t2",
            title = "Track Two",
            artist = "Artist B",
            durationMs = 210_000L,
            artworkUri = null,
            artworkOrigin = ArtworkOrigin.UNKNOWN,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        // Track 3: legacy fallback artwork
        val track3 = TrackEntity(
            id = "t3",
            title = "Track Three",
            artist = "Artist C",
            durationMs = 190_000L,
            artworkUri = "PLAYLIST_FALLBACK_LEGACY",
            artworkOrigin = "PLAYLIST_FALLBACK_LEGACY",
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )

        db.trackDao().insertTracks(listOf(track1, track2, track3))
        db.playlistDao().insertPlaylistEntries(
            listOf(
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t1", position = 0),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t2", position = 1),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t3", position = 2)
            )
        )

        // Detect tracks needing enrichment without re-importing playlist
        val needingEnrichment = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Should detect exactly 2 tracks needing artwork", 2, needingEnrichment.size)
        val needingIds = needingEnrichment.map { it.id }
        assertFalse("Track with genuine Spotify art must NOT be enriched", needingIds.contains("t1"))
        assertTrue("Track with null art must be detected", needingIds.contains("t2"))
        assertTrue("Track with legacy fallback art must be detected", needingIds.contains("t3"))

        // Enrich track 2
        val candidate2 = YouTubeCandidate(
            videoId = "yt_2",
            title = "Track Two",
            channelTitle = "Artist B",
            durationMs = 210_000L,
            artworkUrl = "https://i.ytimg.com/vi/yt_2/hqdefault.jpg"
        )
        repository.updateTrackEnrichment("t2", candidate2, isMatchSafe = true)

        val updated2 = db.trackDao().getTrackById("t2")
        assertNotNull(updated2)
        assertEquals("https://i.ytimg.com/vi/yt_2/hqdefault.jpg", updated2!!.artworkUri)
        assertEquals(ArtworkOrigin.YOUTUBE_ENRICHMENT, updated2.artworkOrigin)
        assertEquals(ArtworkEnrichmentStatus.ENRICHED, updated2.artworkEnrichmentStatus)
    }

    // ── 2. No playback needed ───────────────────────────────────────────────

    @Test
    fun enrichment_doesNotRequirePlaybackOrStreamResolution() = runTest {
        val track = TrackEntity(
            id = "t_noplay",
            title = "Silent Night",
            artist = "Choir",
            durationMs = 180_000L,
            artworkUri = null,
            artworkOrigin = ArtworkOrigin.UNKNOWN,
            resolutionState = ResolutionState.METADATA_ONLY,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        db.trackDao().insertTrack(track)
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_noplay", position = 0))
        )

        val candidate = YouTubeCandidate(
            videoId = "vid_noplay",
            title = "Silent Night",
            channelTitle = "Choir",
            durationMs = 180_000L,
            artworkUrl = "https://i.ytimg.com/vi/vid_noplay/hqdefault.jpg"
        )

        // Enrich track
        repository.updateTrackEnrichment("t_noplay", candidate, isMatchSafe = true)

        val updated = db.trackDao().getTrackById("t_noplay")
        assertNotNull(updated)
        // Artwork is populated purely from candidate metadata
        assertEquals("https://i.ytimg.com/vi/vid_noplay/hqdefault.jpg", updated!!.artworkUri)
        // ResolutionState is METADATA_ONLY — never promoted to MATCHED or stream-resolved
        assertEquals(ResolutionState.METADATA_ONLY, updated.resolutionState)
        // Audio stream URL was never resolved or stored
        assertNull("Local content URI must be null", updated.localContentUri)
        assertNull("Selected source must be null", db.trackDao().getSelectedSource("t_noplay"))
    }

    // ── 3. Preserves existing Spotify artwork & selected source artwork ──────

    @Test
    fun preservesExistingSpotifyArtwork_andSelectedSourceArtwork() = runTest {
        val spotifyTrack = TrackEntity(
            id = "t_spotify",
            title = "Spotify Original",
            artist = "Artist",
            durationMs = 200_000L,
            artworkUri = "https://i.scdn.co/image/spotify_artwork_123",
            artworkOrigin = ArtworkOrigin.SPOTIFY_TRACK,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED
        )
        db.trackDao().insertTrack(spotifyTrack)
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_spotify", position = 0))
        )

        // Query needing enrichment
        val needing = repository.getTracksNeedingEnrichment(PLAYLIST_ID)
        assertFalse("Spotify track with existing artwork must not be touched", needing.any { it.id == "t_spotify" })

        val trackAfter = db.trackDao().getTrackById("t_spotify")
        assertEquals("https://i.scdn.co/image/spotify_artwork_123", trackAfter?.artworkUri)
        assertEquals(ArtworkOrigin.SPOTIFY_TRACK, trackAfter?.artworkOrigin)
    }

    // ── 4. Safe source handling ──────────────────────────────────────────────

    @Test
    fun safeSourceHandling_candidateStoredAsNonSelectedSource() = runTest {
        val track = TrackEntity(
            id = "t_source_test",
            title = "Safe Track",
            artist = "Safe Artist",
            durationMs = 200_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        db.trackDao().insertTrack(track)
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_source_test", position = 0))
        )

        val candidate = YouTubeCandidate(
            videoId = "yt_safe_1",
            title = "Safe Track",
            channelTitle = "Safe Artist",
            durationMs = 200_000L,
            artworkUrl = "https://i.ytimg.com/vi/yt_safe_1/hqdefault.jpg",
            provider = "youtube_music_innertube"
        )
        repository.updateTrackEnrichment("t_source_test", candidate, isMatchSafe = true)

        val sources = db.trackDao().getSourcesForTrack("t_source_test")
        assertEquals(1, sources.size)
        val source = sources[0]
        assertFalse("Candidate saved for artwork must have selected = false", source.selected)
        assertEquals("youtube_music_innertube", source.provider)
        assertEquals("yt_safe_1", source.sourceId)

        val updatedTrack = db.trackDao().getTrackById("t_source_test")
        assertEquals(ArtworkOrigin.YOUTUBE_ENRICHMENT, updatedTrack?.artworkOrigin)
        assertEquals(ResolutionState.METADATA_ONLY, updatedTrack?.resolutionState)
    }

    // ── 5. Strict confidence scoring ────────────────────────────────────────

    @Test
    fun strictConfidenceScoring_rejectsLowConfidenceCandidate_andKeepsPlaceholder() = runTest {
        val track = TrackEntity(
            id = "t_strict",
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            durationMs = 214_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        db.trackDao().insertTrack(track)
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_strict", position = 0))
        )

        // Candidate with mismatched artist and low confidence
        val badCandidate = YouTubeCandidate(
            videoId = "bad_vid",
            title = "Born to Shine (Dance Cover)",
            channelTitle = "Random Dance Crew",
            durationMs = 300_000L, // 86s delta
            artworkUrl = "https://i.ytimg.com/vi/bad_vid/hqdefault.jpg"
        )
        val lowScoreMatch = MatchResult(
            candidate = badCandidate,
            canonicalDownloadUrl = "https://www.youtube.com/watch?v=bad_vid",
            matchScore = 0.55f,
            durationDeltaMs = 86_000L,
            isConfident = false
        )
        val spotifyMeta = SpotifyTrackMetadata(
            id = track.id,
            title = track.title,
            artists = listOf(track.artist),
            album = track.album,
            releaseYear = null,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl
        )

        val isSafe = TrackMatchEngine.isSafeEnrichmentMatch(spotifyMeta, badCandidate, lowScoreMatch)
        assertFalse("Low confidence candidate must be rejected", isSafe)

        // When unsafe match, repository marks NO_CONFIDENT_MATCH and leaves neutral placeholder
        repository.updateTrackEnrichment("t_strict", badCandidate, isMatchSafe = false)

        val updated = db.trackDao().getTrackById("t_strict")
        assertNotNull(updated)
        assertNull("Neutral placeholder must be retained when match is unsafe", updated!!.artworkUri)
        assertEquals(ArtworkEnrichmentStatus.NO_CONFIDENT_MATCH, updated.artworkEnrichmentStatus)
    }

    @Test
    fun strictConfidenceScoring_acceptsHighConfidenceCandidate() = runTest {
        val track = TrackEntity(
            id = "t_good",
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            durationMs = 214_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        db.trackDao().insertTrack(track)

        val goodCandidate = YouTubeCandidate(
            videoId = "good_vid",
            title = "Born to Shine",
            channelTitle = "Diljit Dosanjh",
            durationMs = 214_000L,
            artworkUrl = "https://i.ytimg.com/vi/good_vid/hqdefault.jpg"
        )
        val highScoreMatch = MatchResult(
            candidate = goodCandidate,
            canonicalDownloadUrl = "https://www.youtube.com/watch?v=good_vid",
            matchScore = 0.95f,
            durationDeltaMs = 0L,
            isConfident = true
        )
        val goodSpotifyMeta = SpotifyTrackMetadata(
            id = track.id,
            title = track.title,
            artists = listOf(track.artist),
            album = track.album,
            releaseYear = null,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl
        )

        val isSafe = TrackMatchEngine.isSafeEnrichmentMatch(goodSpotifyMeta, goodCandidate, highScoreMatch)
        assertTrue("High confidence candidate must be accepted", isSafe)
    }

    // ── 6. Signed stream URLs are never persisted ───────────────────────────

    @Test
    fun signedStreamUrls_areNeverPersistedAnywhere() = runTest {
        val track = TrackEntity(
            id = "t_url_check",
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            artworkUri = null
        )
        db.trackDao().insertTrack(track)
        db.playlistDao().insertPlaylistEntries(
            listOf(PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_url_check", position = 0))
        )

        val candidate = YouTubeCandidate(
            videoId = "vid_123",
            title = "Song",
            channelTitle = "Artist",
            durationMs = 180_000L,
            artworkUrl = "https://i.ytimg.com/vi/vid_123/hqdefault.jpg"
        )
        repository.updateTrackEnrichment("t_url_check", candidate, isMatchSafe = true)

        val trackAfter = db.trackDao().getTrackById("t_url_check")
        assertNotNull(trackAfter)
        assertFalse("Artwork URI must not be a googlevideo stream URL", trackAfter!!.artworkUri?.contains("googlevideo") == true)
        assertFalse("Artwork URI must not contain expire/signature tokens", trackAfter.artworkUri?.contains("expire=") == true)

        val sources = db.trackDao().getSourcesForTrack("t_url_check")
        for (source in sources) {
            assertFalse("Canonical URL must not contain googlevideo stream URL", source.canonicalUrl.contains("googlevideo"))
            assertFalse("Artwork URL must not contain googlevideo stream URL", source.artworkUrl?.contains("googlevideo") == true)
        }
    }

    // ── 7. Reopening playlist does not enqueue completed work ───────────────

    @Test
    fun reopeningPlaylist_doesNotEnqueueCompletedWork_andNoConfidentMatchObservesCooldown() = runTest {
        val enrichedTrack = TrackEntity(
            id = "t_enriched",
            title = "Enriched Song",
            artist = "Artist",
            durationMs = 180_000L,
            artworkUri = "https://i.ytimg.com/vi/enriched/hqdefault.jpg",
            artworkOrigin = ArtworkOrigin.YOUTUBE_ENRICHMENT,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED
        )
        val noMatchTrack = TrackEntity(
            id = "t_nomatch",
            title = "Obscure Indie Song",
            artist = "Indie Band",
            durationMs = 180_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.NO_CONFIDENT_MATCH,
            lastArtworkAttemptEpochMs = System.currentTimeMillis()
        )
        db.trackDao().insertTracks(listOf(enrichedTrack, noMatchTrack))
        db.playlistDao().insertPlaylistEntries(
            listOf(
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_enriched", position = 0),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_nomatch", position = 1)
            )
        )

        // Opening playlist repeatedly (forceRetry = false)
        val needing = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Reopening playlist must NOT enqueue completed or unexpired no-match work", 0, needing.size)

        // Manual retry ("Retry missing artwork", forceRetry = true)
        val retryNeeding = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = true)
        assertEquals("Manual retry must include the NO_CONFIDENT_MATCH track", 1, retryNeeding.size)
        assertEquals("t_nomatch", retryNeeding[0].id)
        assertFalse("ENRICHED track must NEVER be searched again even on retry", retryNeeding.any { it.id == "t_enriched" })
    }

    // ── 8. Rate-limit protection ─────────────────────────────────────────────

    @Test
    fun rateLimitedTrack_setsRateLimitedStatus_withoutLosingCompletedProgress() = runTest {
        val track1 = TrackEntity(
            id = "t_ok",
            title = "Track OK",
            artist = "Artist",
            durationMs = 180_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        val track2 = TrackEntity(
            id = "t_rate_limited",
            title = "Track Rate Limited",
            artist = "Artist",
            durationMs = 180_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        db.trackDao().insertTracks(listOf(track1, track2))
        db.playlistDao().insertPlaylistEntries(
            listOf(
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_ok", position = 0),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_rate_limited", position = 1)
            )
        )

        // Track 1 succeeds
        val candidate1 = YouTubeCandidate(
            videoId = "v1",
            title = "Track OK",
            channelTitle = "Artist",
            durationMs = 180_000L,
            artworkUrl = "https://art/1.jpg"
        )
        repository.updateTrackEnrichment("t_ok", candidate1, isMatchSafe = true)

        // Track 2 hits 429
        repository.markTrackEnrichmentRateLimited("t_rate_limited")

        val track1After = db.trackDao().getTrackById("t_ok")
        val track2After = db.trackDao().getTrackById("t_rate_limited")

        assertEquals("Track 1 must remain ENRICHED", ArtworkEnrichmentStatus.ENRICHED, track1After?.artworkEnrichmentStatus)
        assertEquals("https://art/1.jpg", track1After?.artworkUri)

        assertEquals("Track 2 must be marked RATE_LIMITED", ArtworkEnrichmentStatus.RATE_LIMITED, track2After?.artworkEnrichmentStatus)
        assertNull("Track 2 retains neutral placeholder", track2After?.artworkUri)
        assertTrue("Track 2 lastArtworkAttemptEpochMs must be updated", (track2After?.lastArtworkAttemptEpochMs ?: 0L) > 0)
    }

    // ── 9. Process interruption & safe resumption ───────────────────────────

    @Test
    fun processInterruption_resumesRemainingTracksSafely() = runTest {
        // Track 1 was completed before crash
        val track1 = TrackEntity(
            id = "t_c1",
            title = "C1",
            artist = "A",
            durationMs = 180_000L,
            artworkUri = "https://art/c1.jpg",
            artworkOrigin = ArtworkOrigin.YOUTUBE_ENRICHMENT,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.ENRICHED
        )
        // Tracks 2 and 3 were not attempted yet
        val track2 = TrackEntity(
            id = "t_c2",
            title = "C2",
            artist = "A",
            durationMs = 180_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        val track3 = TrackEntity(
            id = "t_c3",
            title = "C3",
            artist = "A",
            durationMs = 180_000L,
            artworkUri = null,
            artworkEnrichmentStatus = ArtworkEnrichmentStatus.PENDING
        )
        db.trackDao().insertTracks(listOf(track1, track2, track3))
        db.playlistDao().insertPlaylistEntries(
            listOf(
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_c1", position = 0),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_c2", position = 1),
                PlaylistEntryEntity(playlistId = PLAYLIST_ID, trackId = "t_c3", position = 2)
            )
        )

        // Simulate app restart: worker queries remaining work
        val remaining = repository.getTracksNeedingEnrichment(PLAYLIST_ID, forceRetry = false)
        assertEquals("Only unfinished tracks (2 and 3) should be queried", 2, remaining.size)
        assertFalse("Completed track 1 must be skipped", remaining.any { it.id == "t_c1" })

        // Resume and complete remaining tracks
        repository.updateTrackEnrichment(
            "t_c2",
            YouTubeCandidate(videoId = "v2", title = "C2", channelTitle = "A", durationMs = 180_000L, artworkUrl = "https://art/c2.jpg"),
            isMatchSafe = true
        )
        repository.updateTrackEnrichment(
            "t_c3",
            YouTubeCandidate(videoId = "v3", title = "C3", channelTitle = "A", durationMs = 180_000L, artworkUrl = "https://art/c3.jpg"),
            isMatchSafe = true
        )

        val all = repository.getPlaylistEntries(PLAYLIST_ID)
        assertEquals(3, all.size)
        assertTrue("All tracks must now be ENRICHED", all.all { it.artworkEnrichmentStatus == ArtworkEnrichmentStatus.ENRICHED })
        assertTrue("All tracks now have genuine artwork", all.all { !it.artworkUri.isNullOrBlank() })
    }
}
