package com.notify.download.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaylistExtractorTest {

    @Test
    fun extractPlaylistId_standardDesktopUrl_extractsCorrectId() {
        val url = "https://www.youtube.com/playlist?list=PL1234567890abcdef"
        val id = YouTubePlaylistExtractor.extractPlaylistId(url)
        assertEquals("PL1234567890abcdef", id)
    }

    @Test
    fun extractPlaylistId_musicYouTubeUrl_extractsCorrectId() {
        val url = "https://music.youtube.com/playlist?list=PLabc_xyz-123"
        val id = YouTubePlaylistExtractor.extractPlaylistId(url)
        assertEquals("PLabc_xyz-123", id)
    }

    @Test
    fun extractPlaylistId_watchUrlWithListParam_extractsCorrectId() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLrAlG_n4K4lWj2vE7N0wK4ZzG7rF4e"
        val id = YouTubePlaylistExtractor.extractPlaylistId(url)
        assertEquals("PLrAlG_n4K4lWj2vE7N0wK4ZzG7rF4e", id)
    }

    @Test
    fun extractPlaylistId_directId_extractsCorrectId() {
        val directId = "PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"
        val id = YouTubePlaylistExtractor.extractPlaylistId(directId)
        assertEquals("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj", id)
    }

    @Test
    fun extractPlaylistId_albumPlaylistId_extractsCorrectId() {
        val albumId = "OLAK5uy_k123456"
        val id = YouTubePlaylistExtractor.extractPlaylistId(albumId)
        assertEquals("OLAK5uy_k123456", id)
    }

    @Test
    fun extractPlaylistId_invalidUrl_returnsNull() {
        assertNull(YouTubePlaylistExtractor.extractPlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubePlaylistExtractor.extractPlaylistId("https://spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(YouTubePlaylistExtractor.extractPlaylistId(""))
        assertNull(YouTubePlaylistExtractor.extractPlaylistId("   "))
        assertNull(YouTubePlaylistExtractor.extractPlaylistId("just some random text"))
    }
}
