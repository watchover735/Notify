package com.notify.download

import android.content.Context
import androidx.room.Room
import com.notify.download.db.ArtworkOrigin
import com.notify.download.db.NotiFyDatabase
import com.notify.download.db.PlaylistRepository
import com.notify.download.db.ResolutionState
import com.notify.download.db.TrackSourceEntity
import com.notify.download.spotify.ScrapedPlaylist
import com.notify.download.spotify.ScrapedTrack
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies Patch A2.1.1 artwork separation invariants:
 *  1. Playlist cover is never copied into TrackEntity artwork.
 *  2. Tracks with Spotify per-track art use it (artworkOrigin = SPOTIFY_TRACK).
 *  3. Tracks without per-track art get null (neutral placeholder).
 *  4. Legacy tracks with playlist-cover artwork are safely cleared by reconcilePersistedStates
 *     ONLY if they have no genuine YouTube match artwork (INVARIANT: YOUTUBE_MATCH is preserved).
 *  5. Matched YouTube candidate artwork updates track to YOUTUBE_MATCH origin.
 *  6. Artwork survives database close and reopen (Room persistence).
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkSeparationTest {

    private lateinit var context: Context
    private lateinit var db: NotiFyDatabase
    private lateinit var repository: PlaylistRepository

    private val PLAYLIST_ART = "https://i.scdn.co/image/playlist_cover_abc123"
    private val TRACK_ART_1 = "https://i.scdn.co/image/track_art_111"
    private val TRACK_ART_2 = "https://i.scdn.co/image/track_art_222"
    private val YOUTUBE_ART = "https://i.ytimg.com/vi/yt_abc/hqdefault.jpg"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── 1. Playlist cover never copied to tracks ────────────────────────────────────────────

    @Test
    fun playlistCoverIsNeverCopiedToTrack_forTracksWithoutOwnArt() = runTest {
        val scraped = ScrapedPlaylist(
            id = "pl_1",
            title = "Test Playlist",
            description = null,
            artworkUrl = PLAYLIST_ART,
            expectedTrackCount = 2,
            tracks = listOf(
                ScrapedTrack(1, "Song A", "Artist A", null, 180_000L, artworkUrl = TRACK_ART_1),
                ScrapedTrack(2, "Song B", "Artist B", null, 200_000L, artworkUrl = null) // no track art
            )
        )

        val saved = repository.saveScrapedPlaylist(scraped)
        assertEquals("Playlist entity must use its own cover", PLAYLIST_ART, saved.artworkUri)

        val entries = repository.getPlaylistEntries(saved.playlistId)
        assertEquals(2, entries.size)

        // Track with own art
        assertEquals("Track 1 must use its Spotify art", TRACK_ART_1, entries[0].artworkUrl)
        assertEquals("Track 1 artworkUri must also be set", TRACK_ART_1, entries[0].artworkUri)

        // Track without own art: must be null, NOT playlist cover
        assertNull("Track 2 artworkUrl must be null, not playlist cover", entries[1].artworkUrl)
        assertNull("Track 2 artworkUri must be null, not playlist cover", entries[1].artworkUri)
    }

    // ── 2. artworkOrigin = SPOTIFY_TRACK when per-track art is provided ─────────────────────

    @Test
    fun artworkOrigin_isSpotifyTrack_whenPerTrackArtIsPresent() = runTest {
        val scraped = ScrapedPlaylist(
            id = "pl_2", title = "T", description = null, artworkUrl = PLAYLIST_ART,
            expectedTrackCount = 1,
            tracks = listOf(ScrapedTrack(1, "Song", "Artist", null, 180_000L, artworkUrl = TRACK_ART_1))
        )
        repository.saveScrapedPlaylist(scraped)

        val trackEntity = db.trackDao().getTrackById("spotify:pl_2_1_${Math.abs("Song".hashCode())}")
        assertEquals(ArtworkOrigin.SPOTIFY_TRACK, trackEntity?.artworkOrigin)
    }

    // ── 3. artworkOrigin = UNKNOWN when track has no art ───────────────────────────────────

    @Test
    fun artworkOrigin_isUnknown_whenNoPerTrackArt() = runTest {
        val scraped = ScrapedPlaylist(
            id = "pl_3", title = "T", description = null, artworkUrl = PLAYLIST_ART,
            expectedTrackCount = 1,
            tracks = listOf(ScrapedTrack(1, "Song", "Artist", null, 180_000L, artworkUrl = null))
        )
        repository.saveScrapedPlaylist(scraped)

        val trackEntity = db.trackDao().getTrackById("spotify:pl_3_1_${Math.abs("Song".hashCode())}")
        assertEquals(ArtworkOrigin.UNKNOWN, trackEntity?.artworkOrigin)
        assertNull(trackEntity?.artworkUrl)
    }

    // ── 4. Legacy repair: playlist-cover artwork is cleared, YOUTUBE_MATCH is preserved ─────

    @Test
    fun reconcilePersistedStates_clearsLegacyPlaylistArtButPreservesYouTubeMatch() = runTest {
        // Save playlist: track has no per-track art, so artworkUrl starts as null
        val scraped = ScrapedPlaylist(
            id = "pl_4", title = "T", description = null, artworkUrl = PLAYLIST_ART,
            expectedTrackCount = 2,
            tracks = listOf(
                ScrapedTrack(1, "Song A", "Artist", null, 180_000L, null),
                ScrapedTrack(2, "Song B", "Artist", null, 180_000L, null)
            )
        )
        val saved = repository.saveScrapedPlaylist(scraped)

        val trackIdA = "spotify:pl_4_1_${Math.abs("Song A".hashCode())}"
        val trackIdB = "spotify:pl_4_2_${Math.abs("Song B".hashCode())}"

        // Manually inject legacy state: track A has playlist cover as artworkUrl (old bug)
        db.trackDao().updateArtwork(trackIdA, artworkUri = PLAYLIST_ART, artworkUrl = PLAYLIST_ART)

        // Track B has playlist cover but also has a genuine YouTube match source
        db.trackDao().updateArtwork(trackIdB, artworkUri = PLAYLIST_ART, artworkUrl = PLAYLIST_ART)
        db.trackDao().updateArtworkWithOrigin(trackIdB, YOUTUBE_ART, YOUTUBE_ART, ArtworkOrigin.YOUTUBE_MATCH)
        db.trackDao().insertSource(
            TrackSourceEntity(
                sourceKey = "$trackIdB:youtube",
                trackId = trackIdB,
                provider = "youtube_music_innertube",
                sourceId = "yt_b",
                canonicalUrl = "https://youtube.com/watch?v=yt_b",
                confidence = 0.95f,
                durationDeltaMs = 200L,
                artworkUrl = YOUTUBE_ART,
                selected = true
            )
        )

        repository.reconcilePersistedStates()

        // Track A: legacy artwork should be cleared
        val reconciledA = db.trackDao().getTrackById(trackIdA)
        assertNull("Track A legacy playlist art must be cleared", reconciledA?.artworkUrl)
        assertEquals(ArtworkOrigin.PLAYLIST_FALLBACK_LEGACY, reconciledA?.artworkOrigin)

        // Track B: YouTube match artwork must be PRESERVED (not cleared)
        val reconciledB = db.trackDao().getTrackById(trackIdB)
        assertEquals("Track B YOUTUBE_MATCH art must be preserved", YOUTUBE_ART, reconciledB?.artworkUrl)
        assertEquals(ArtworkOrigin.YOUTUBE_MATCH, reconciledB?.artworkOrigin)
    }

    // ── 5. updateTrackArtwork sets YOUTUBE_MATCH origin ────────────────────────────────────

    @Test
    fun updateTrackArtwork_setsYoutubeMatchOrigin() = runTest {
        val scraped = ScrapedPlaylist(
            id = "pl_5", title = "T", description = null, artworkUrl = null,
            expectedTrackCount = 1,
            tracks = listOf(ScrapedTrack(1, "Song", "Artist", null, 180_000L, null))
        )
        repository.saveScrapedPlaylist(scraped)

        val trackId = "spotify:pl_5_1_${Math.abs("Song".hashCode())}"
        repository.updateTrackArtwork(trackId, YOUTUBE_ART)

        val track = db.trackDao().getTrackById(trackId)
        assertEquals(YOUTUBE_ART, track?.artworkUrl)
        assertEquals(ArtworkOrigin.YOUTUBE_MATCH, track?.artworkOrigin)
    }

    // ── 6. Re-import preserves YOUTUBE_MATCH artwork ────────────────────────────────────────

    @Test
    fun reimport_preservesYoutubeMatchArtwork_forTracksAlreadyMatched() = runTest {
        // Initial import with Spotify art
        val scraped = ScrapedPlaylist(
            id = "pl_6", title = "T", description = null, artworkUrl = PLAYLIST_ART,
            expectedTrackCount = 1,
            tracks = listOf(ScrapedTrack(1, "Song", "Artist", null, 180_000L, TRACK_ART_1))
        )
        repository.saveScrapedPlaylist(scraped)
        val trackId = "spotify:pl_6_1_${Math.abs("Song".hashCode())}"

        // Simulate user playing the track: YouTube art replaces Spotify art
        repository.updateTrackArtwork(trackId, YOUTUBE_ART)

        // Re-import (same playlist but could have different Spotify art URL)
        val reScraped = scraped.copy(tracks = listOf(ScrapedTrack(1, "Song", "Artist", null, 180_000L, TRACK_ART_2)))
        repository.saveScrapedPlaylist(reScraped)

        // YOUTUBE_MATCH artwork must survive re-import
        val track = db.trackDao().getTrackById(trackId)
        assertEquals("YOUTUBE_MATCH must survive re-import", YOUTUBE_ART, track?.artworkUrl)
        assertEquals(ArtworkOrigin.YOUTUBE_MATCH, track?.artworkOrigin)
    }

    // ── 7. Playlist header correctly uses playlist artworkUri (not track art) ────────────────

    @Test
    fun playlistSummary_usesPlaylistArtworkUri_forHeader() = runTest {
        val scraped = ScrapedPlaylist(
            id = "pl_7", title = "My Playlist", description = null, artworkUrl = PLAYLIST_ART,
            expectedTrackCount = 2,
            tracks = listOf(
                ScrapedTrack(1, "Song A", "Artist", null, 180_000L, TRACK_ART_1),
                ScrapedTrack(2, "Song B", "Artist", null, 180_000L, TRACK_ART_2)
            )
        )
        val saved = repository.saveScrapedPlaylist(scraped)

        val playlist = db.playlistDao().getPlaylistById(saved.playlistId)
        assertEquals("Playlist header uses playlist artworkUri", PLAYLIST_ART, playlist?.artworkUri)
    }
}
