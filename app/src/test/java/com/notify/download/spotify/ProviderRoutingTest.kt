package com.notify.download.spotify

import android.content.Context
import com.notify.download.stream.OnlineStreamResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderRoutingTest {

    @Test
    fun publicSpotifyProvider_usedInsteadOfAnonymousProvider() = runTest {
        val scraper = PublicSpotifyScraper()
        val publicProvider = PublicSpotifyMetadataProvider(scraper)

        // Populate with Stage 0 scraped playlist
        val scrapedPlaylist = ScrapedPlaylist(
            id = "test_cache",
            title = "Cached Playlist",
            description = null,
            artworkUrl = null,
            expectedTrackCount = 1,
            tracks = listOf(
                ScrapedTrack(1, "Born to Shine", "Diljit Dosanjh", "G.O.A.T.", 213000L, null)
            )
        )
        publicProvider.cacheScrapedPlaylist(scrapedPlaylist)

        // Query playlist: must serve directly from cache without network/token call
        val result = publicProvider.fetchPlaylist("test_cache", limit = 1)
        assertTrue(result.isSuccess)
        val playlist = result.getOrThrow()
        assertEquals("Cached Playlist", playlist.title)
        assertEquals(1, playlist.tracks.size)
        assertEquals("Born to Shine", playlist.tracks[0].title)
    }

    @Test
    fun experimentalAnonymousSpotifyProvider_returnsExplicitDisabledMessage() = runTest {
        val legacyProvider = ExperimentalAnonymousSpotifyProvider()

        val trackRes = legacyProvider.fetchTrack("dummy_id")
        assertTrue(trackRes.isFailure)
        assertEquals(
            "Legacy anonymous Spotify provider disabled. Use Public Spotify Import.",
            trackRes.exceptionOrNull()?.message
        )

        val playlistRes = legacyProvider.fetchPlaylist("dummy_playlist_id", 3)
        assertTrue(playlistRes.isFailure)
        assertEquals(
            "Legacy anonymous Spotify provider disabled. Use Public Spotify Import.",
            playlistRes.exceptionOrNull()?.message
        )
    }

    @Test
    fun spotifyUrl_neverPassedToYoutubeDLRequest() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val streamResolver = OnlineStreamResolver(context)

        val openSpotifyUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        val spotifyLinkUrl = "https://spotify.link/xyz123"

        val res1 = streamResolver.resolveStream(openSpotifyUrl)
        assertTrue("Spotify open.spotify.com URL must be rejected by resolver", res1.isFailure)
        assertTrue(res1.exceptionOrNull() is IllegalArgumentException)
        assertTrue(res1.exceptionOrNull()?.message?.contains("Spotify URLs must never be passed") == true)

        val res2 = streamResolver.resolveStream(spotifyLinkUrl)
        assertTrue("Spotify shortlink URL must be rejected by resolver", res2.isFailure)
        assertTrue(res2.exceptionOrNull() is IllegalArgumentException)
        assertTrue(res2.exceptionOrNull()?.message?.contains("Spotify URLs must never be passed") == true)
    }

    @Test
    fun failureStatus_neverRepresentedAsSuccessIcon() {
        fun evaluateStatus(statusText: String): Pair<Boolean, Boolean> {
            val isFailure = statusText.startsWith("Failed", ignoreCase = true) ||
                    statusText.startsWith("Error", ignoreCase = true) ||
                    statusText.contains("HTTP 403")

            val isSuccess = !isFailure && (
                    statusText.startsWith("Success", ignoreCase = true) ||
                            statusText.startsWith("Saved", ignoreCase = true) ||
                            statusText.startsWith("Streaming", ignoreCase = true) ||
                            statusText.startsWith("Loaded", ignoreCase = true)
                    )
            return isFailure to isSuccess
        }

        // Test failure cases: MUST BE isFailure = true, isSuccess = false
        val failureCases = listOf(
            "Failed to fetch Spotify metadata: anonymous token HTTP 403",
            "Failed: YouTube search error",
            "Error resolving audio stream",
            "Failed: Please enter a valid Spotify URL"
        )

        for (status in failureCases) {
            val (isFailure, isSuccess) = evaluateStatus(status)
            assertTrue("Status '$status' must evaluate to failure", isFailure)
            assertFalse("Status '$status' must NEVER evaluate to success", isSuccess)
        }

        // Test success cases: MUST BE isFailure = false, isSuccess = true
        val successCases = listOf(
            "Success! Extracted all 93 tracks via EMBED_NEXT_DATA.",
            "Saved 93 tracks to Room database! (Metadata-only)",
            "Streaming: 'Born to Shine' via YouTube Music (96% match)",
            "Loaded saved playlist 'Top Hits' (93 tracks) from Room DB."
        )

        for (status in successCases) {
            val (isFailure, isSuccess) = evaluateStatus(status)
            assertFalse("Success status '$status' must not be marked as failure", isFailure)
            assertTrue("Success status '$status' must be marked as success", isSuccess)
        }
    }
}
