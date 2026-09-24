package com.notify.download.matcher

import com.notify.download.spotify.SpotifyTrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchEngineTest {

    private val sampleTrack = SpotifyTrackMetadata(
        id = "4cOdK2wGLETKBW3PvgPWqT",
        title = "Never Gonna Give You Up",
        artists = listOf("Rick Astley"),
        album = "Whenever You Need Somebody",
        releaseYear = "1987",
        durationMs = 213000L, // 3m 33s
        artworkUrl = "https://example.com/art.jpg"
    )

    @Test
    fun normalizeText_removesJunkAndDiacritics() {
        val raw = "Never Gonna Give You Up (Official Music Video) [4K HD]"
        val normalized = TrackMatchEngine.normalizeText(raw)
        assertEquals("never gonna give you up", normalized)

        val accented = "Café Del Mar (Official Audio)"
        val normAccented = TrackMatchEngine.normalizeText(accented)
        assertEquals("cafe del mar", normAccented)
    }

    @Test
    fun forbiddenWords_areSeverelyPenalized() {
        val normalCandidate = YouTubeCandidate(
            videoId = "dQw4w9WgXcQ",
            title = "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            channelTitle = "Rick Astley",
            durationMs = 213000L
        )

        val karaokeCandidate = YouTubeCandidate(
            videoId = "karaoke123",
            title = "Rick Astley - Never Gonna Give You Up (Karaoke Version)",
            channelTitle = "Sing Along Karaoke",
            durationMs = 213000L
        )

        val normalScore = TrackMatchEngine.scoreCandidate(sampleTrack, normalCandidate)
        val karaokeScore = TrackMatchEngine.scoreCandidate(sampleTrack, karaokeCandidate)

        assertTrue("Normal score ($normalScore) must be higher than karaoke score ($karaokeScore)", normalScore > karaokeScore)
        assertTrue("Karaoke score should be penalized heavily", karaokeScore < 0.50f)
    }

    @Test
    fun durationDelta_closerDurationGivesHigherScore() {
        val exactDuration = YouTubeCandidate(
            videoId = "exact1",
            title = "Never Gonna Give You Up",
            channelTitle = "Rick Astley",
            durationMs = 213000L
        )

        val extendedDuration = YouTubeCandidate(
            videoId = "extended1",
            title = "Never Gonna Give You Up",
            channelTitle = "Rick Astley",
            durationMs = 350000L // 2+ minutes longer
        )

        val exactScore = TrackMatchEngine.scoreCandidate(sampleTrack, exactDuration)
        val extendedScore = TrackMatchEngine.scoreCandidate(sampleTrack, extendedDuration)

        assertTrue("Exact score ($exactScore) must be substantially higher than extended ($extendedScore)", exactScore > extendedScore)
    }

    @Test
    fun findBestMatch_returnsCanonicalYouTubeUrl() {
        val candidates = listOf(
            YouTubeCandidate("vid_bad", "Never Gonna Give You Up (Cover)", "Cover Channel", 213000L),
            YouTubeCandidate("vid_good", "Rick Astley - Never Gonna Give You Up", "Rick Astley - Topic", 213500L),
            YouTubeCandidate("vid_slowed", "Never Gonna Give You Up [Slowed + Reverb]", "Slowed Tracks", 270000L)
        )

        val match = TrackMatchEngine.findBestMatch(sampleTrack, candidates)
        assertNotNull(match)
        assertEquals("vid_good", match!!.candidate.videoId)
        // Must strictly start with https://www.youtube.com/watch?v=
        assertEquals("https://www.youtube.com/watch?v=vid_good", match.canonicalDownloadUrl)
        assertTrue(match.isConfident)
    }
}
