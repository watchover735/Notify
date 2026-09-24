package com.notify.download.spotify

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyUrlResolverTest {

    @Test
    fun parseSpotifyTrackUrl_extractsCleanId() = runTest {
        val url = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123xyz"
        val resolved = SpotifyUrlResolver.resolveAndClassify(url)

        assertTrue(resolved is ResolvedUrl.SpotifyTrack)
        val track = resolved as ResolvedUrl.SpotifyTrack
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", track.trackId)
        assertEquals("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", track.cleanUrl)
    }

    @Test
    fun parseSpotifyPlaylistUrl_extractsCleanId() = runTest {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=dummy"
        val resolved = SpotifyUrlResolver.resolveAndClassify(url)

        assertTrue(resolved is ResolvedUrl.SpotifyPlaylist)
        val playlist = resolved as ResolvedUrl.SpotifyPlaylist
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", playlist.playlistId)
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", playlist.cleanUrl)
    }

    @Test
    fun parseSpotifyAlbumUrl_extractsCleanId() = runTest {
        val url = "https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"
        val resolved = SpotifyUrlResolver.resolveAndClassify(url)

        assertTrue(resolved is ResolvedUrl.SpotifyAlbum)
        val album = resolved as ResolvedUrl.SpotifyAlbum
        assertEquals("1DFixLWuPkv3KT3TnV35m3", album.albumId)
    }

    @Test
    fun parseYouTubeUrls_normalizesToCanonicalWatchUrl() = runTest {
        val shortUrl = "https://youtu.be/kJQP7kiw5Fk?si=123"
        val shortResolved = SpotifyUrlResolver.resolveAndClassify(shortUrl)
        assertTrue(shortResolved is ResolvedUrl.YouTube)
        assertEquals("https://www.youtube.com/watch?v=kJQP7kiw5Fk", (shortResolved as ResolvedUrl.YouTube).canonicalUrl)

        val musicUrl = "https://music.youtube.com/watch?v=kJQP7kiw5Fk&feature=share"
        val musicResolved = SpotifyUrlResolver.resolveAndClassify(musicUrl)
        assertTrue(musicResolved is ResolvedUrl.YouTube)
        assertEquals("https://www.youtube.com/watch?v=kJQP7kiw5Fk", (musicResolved as ResolvedUrl.YouTube).canonicalUrl)
    }

    @Test
    fun parseUnsupportedHosts_returnsUnsupported() = runTest {
        val unknownUrl = "https://example.com/audio.mp3"
        val resolved = SpotifyUrlResolver.resolveAndClassify(unknownUrl)
        assertTrue(resolved is ResolvedUrl.Unsupported)
    }

    @Test
    fun parseInvalidScheme_returnsUnsupported() = runTest {
        val ftpUrl = "ftp://open.spotify.com/track/123"
        val resolved = SpotifyUrlResolver.resolveAndClassify(ftpUrl)
        assertTrue(resolved is ResolvedUrl.Unsupported)
    }
}
