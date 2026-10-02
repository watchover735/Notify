package com.notify.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkResolutionTest {

    @Test
    fun nullOrBlank_returnsNullOrEmpty() {
        assertNull(ArtworkResolution.highResArtwork(null))
        assertNull(ArtworkResolution.highResArtwork(""))
        assertNull(ArtworkResolution.highResArtwork("   "))
        assertTrue(ArtworkResolution.highResArtworkCandidates(null).isEmpty())
    }

    @Test
    fun localAndContentUris_returnedUnchanged() {
        val contentUri = "content://media/external/audio/albumart/123"
        val fileUri = "file:///storage/emulated/0/Music/cover.jpg"

        assertEquals(contentUri, ArtworkResolution.highResArtwork(contentUri))
        assertEquals(listOf(contentUri), ArtworkResolution.highResArtworkCandidates(contentUri))

        assertEquals(fileUri, ArtworkResolution.highResArtwork(fileUri))
        assertEquals(listOf(fileUri), ArtworkResolution.highResArtworkCandidates(fileUri))
    }

    @Test
    fun jioSaavn_upgrades50x50And150x150To500x500() {
        val lowRes50 = "https://c.saavncdn.com/123/Song-Title-Hindi-50x50.jpg"
        val lowRes150 = "https://c.saavncdn.com/123/Song-Title-Hindi-150x150.jpg"
        val highRes500 = "https://c.saavncdn.com/123/Song-Title-Hindi-500x500.jpg"

        assertEquals(highRes500, ArtworkResolution.highResArtwork(lowRes50))
        assertEquals(highRes500, ArtworkResolution.highResArtwork(lowRes150))
        assertEquals(highRes500, ArtworkResolution.highResArtwork(highRes500))

        val candidates50 = ArtworkResolution.highResArtworkCandidates(lowRes50)
        assertEquals(highRes500, candidates50[0])
        assertEquals(lowRes50, candidates50[1])
    }

    @Test
    fun deezer_upgradesSmallAndMediumToCoverXlOrBig() {
        val deezerUrl = "https://e-cdns-images.dzcdn.net/images/cover/2e018122cb56986277102d2041a592c8/56x56-000000-80-0-0.jpg"

        // Large target (Now Playing: > 500px)
        val highResLarge = ArtworkResolution.highResArtwork(deezerUrl, targetPx = 1080)
        assertTrue(highResLarge!!.contains("/1000x1000-"))

        // Standard target (List thumbnail: <= 500px)
        val highResSmall = ArtworkResolution.highResArtwork(deezerUrl, targetPx = 256)
        assertTrue(highResSmall!!.contains("/500x500-"))

        val candidates = ArtworkResolution.highResArtworkCandidates(deezerUrl, targetPx = 1080)
        assertTrue(candidates[0].contains("1000x1000"))
        assertTrue(candidates[1].contains("500x500"))
    }

    @Test
    fun soundCloud_upgradesLargeToT500() {
        val scUrl = "https://i1.sndcdn.com/artworks-000123456789-abcdef-large.jpg"
        val expected = "https://i1.sndcdn.com/artworks-000123456789-abcdef-t500x500.jpg"

        assertEquals(expected, ArtworkResolution.highResArtwork(scUrl))
        val candidates = ArtworkResolution.highResArtworkCandidates(scUrl)
        assertEquals(expected, candidates[0])
        assertEquals(scUrl, candidates[1])
    }

    @Test
    fun youtube_generatesOrderedCandidateFallbackChain() {
        val ytUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"

        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", ArtworkResolution.highResArtwork(ytUrl))

        val candidates = ArtworkResolution.highResArtworkCandidates(ytUrl)
        assertEquals(3, candidates.size)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", candidates[0])
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg", candidates[1])
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", candidates[2])
    }

    @Test
    fun googleusercontent_upgradesW60ToW544() {
        val lowRes = "https://lh3.googleusercontent.com/some_hash=w60-h60-l90-rj"
        val expected = "https://lh3.googleusercontent.com/some_hash=w544-h544-l90-rj"

        assertEquals(expected, ArtworkResolution.highResArtwork(lowRes))
        val candidates = ArtworkResolution.highResArtworkCandidates(lowRes)
        assertEquals(expected, candidates[0])
        assertEquals(lowRes, candidates[1])
    }

    @Test
    fun unknownHost_preservedUnchanged() {
        val customUrl = "https://example.com/custom/album/cover.png"
        assertEquals(customUrl, ArtworkResolution.highResArtwork(customUrl))
        assertEquals(listOf(customUrl), ArtworkResolution.highResArtworkCandidates(customUrl))
    }
}
