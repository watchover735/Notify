package com.notify.download.spotify

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicSpotifyScraperTest {

    private val scraper = PublicSpotifyScraper()

    @Test
    fun parseHtml_nextDataStrategy_parsesPlaylistAndTracks() {
        val sampleNextDataJson = """
        {
          "props": {
            "pageProps": {
              "state": {
                "data": {
                  "entity": {
                    "name": "Lo-Fi Beats Test",
                    "description": "Chill beats for testing",
                    "totalTracks": 3,
                    "coverArtUrl": "https://i.scdn.co/image/ab67616d0000b273sample",
                    "trackList": [
                      {
                        "title": "Midnight City Walk",
                        "subtitle": "Lo-Fi Dreamer",
                        "duration": 154000,
                        "album": "Night Walks",
                        "artworkUrl": "https://i.scdn.co/image/art1"
                      },
                      {
                        "name": "Coffee & Rain",
                        "artists": [{"name": "Rainmaker"}, {"name": "Chill Cat"}],
                        "duration_ms": 182000,
                        "album": "Café Sessions"
                      },
                      {
                        "title": "Stargazing",
                        "subtitle": "Astral Wave",
                        "duration": 210000
                      }
                    ]
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Lo-Fi Beats Test | Spotify</title></head>
            <body>
            <script id="__NEXT_DATA__" type="application/json">$sampleNextDataJson</script>
            </body>
            </html>
        """.trimIndent()

        val parseResult = scraper.parseHtml("test_playlist_1", html, isEmbed = true)

        assertEquals("EMBED_NEXT_DATA", parseResult.strategyUsed)
        assertNotNull(parseResult.playlist)

        val playlist = parseResult.playlist!!
        assertEquals("test_playlist_1", playlist.id)
        assertEquals("Lo-Fi Beats Test", playlist.title)
        assertEquals("Chill beats for testing", playlist.description)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273sample", playlist.artworkUrl)
        assertEquals(3, playlist.expectedTrackCount)
        assertEquals(3, playlist.extractedTrackCount)
        assertTrue(playlist.isComplete)

        // Verify tracks
        val track1 = playlist.tracks[0]
        assertEquals(1, track1.position)
        assertEquals("Midnight City Walk", track1.title)
        assertEquals("Lo-Fi Dreamer", track1.artist)
        assertEquals("Night Walks", track1.album)
        assertEquals(154000L, track1.durationMs)
        assertEquals("2:34", track1.formattedDuration)

        val track2 = playlist.tracks[1]
        assertEquals(2, track2.position)
        assertEquals("Coffee & Rain", track2.title)
        assertEquals("Rainmaker, Chill Cat", track2.artist)
        assertEquals(182000L, track2.durationMs)
        assertEquals("3:02", track2.formattedDuration)

        val track3 = playlist.tracks[2]
        assertEquals(3, track3.position)
        assertEquals("Stargazing", track3.title)
        assertEquals("Astral Wave", track3.artist)
        assertEquals("3:30", track3.formattedDuration)
    }

    @Test
    fun parseHtml_initialDataBase64Strategy_parsesCorrectly() {
        val sampleJson = """
        {
          "props": {
            "pageProps": {
              "entity": {
                "name": "Base64 Playlist",
                "totalTracks": 2,
                "trackList": [
                  {
                    "title": "Track One",
                    "subtitle": "Artist A",
                    "duration": 120000
                  },
                  {
                    "title": "Track Two",
                    "subtitle": "Artist B",
                    "duration": 240000
                  }
                ]
              }
            }
          }
        }
        """.trimIndent()

        val base64Encoded = Base64.getEncoder().encodeToString(sampleJson.toByteArray())

        val html = """
            <html>
            <body>
            <script id="initial-data" type="text/plain">$base64Encoded</script>
            </body>
            </html>
        """.trimIndent()

        val parseResult = scraper.parseHtml("test_b64", html, isEmbed = true)

        assertEquals("EMBED_INITIAL_DATA", parseResult.strategyUsed)
        assertNotNull(parseResult.playlist)
        assertEquals("Base64 Playlist", parseResult.playlist!!.title)
        assertEquals(2, parseResult.playlist!!.tracks.size)
    }

    @Test
    fun parseHtml_incompleteExtraction_detectsMismatch() {
        val sampleJson = """
        {
          "props": {
            "pageProps": {
              "entity": {
                "name": "Huge 100-Track Playlist",
                "totalTracks": 100,
                "trackList": [
                  {
                    "title": "Track 1",
                    "subtitle": "Artist 1",
                    "duration": 180000
                  }
                ]
              }
            }
          }
        }
        """.trimIndent()

        val html = """<script id="__NEXT_DATA__" type="application/json">$sampleJson</script>"""
        val parseResult = scraper.parseHtml("huge_pl", html, isEmbed = true)

        assertNotNull(parseResult.playlist)
        val playlist = parseResult.playlist!!
        assertEquals(100, playlist.expectedTrackCount)
        assertEquals(1, playlist.extractedTrackCount)
        assertFalse(playlist.isComplete)
    }

    @Test
    fun parseHtml_noValidJson_returnsNoParseMatch() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Spotify</title></head>
            <body>
            <p>Something went wrong</p>
            </body>
            </html>
        """.trimIndent()

        val parseResult = scraper.parseHtml("fail_pl", html, isEmbed = true)
        assertEquals("NO_PARSE_MATCH", parseResult.strategyUsed)
        assertNull(parseResult.playlist)
    }

    @Test
    fun scrapedTrack_formattedDuration_handlesEdgeCases() {
        val zeroDuration = ScrapedTrack(1, "T1", "A1", null, 0L, null)
        assertEquals("--:--", zeroDuration.formattedDuration)

        val negativeDuration = ScrapedTrack(2, "T2", "A2", null, -500L, null)
        assertEquals("--:--", negativeDuration.formattedDuration)

        val exactMinute = ScrapedTrack(3, "T3", "A3", null, 60000L, null)
        assertEquals("1:00", exactMinute.formattedDuration)

        val complexDuration = ScrapedTrack(4, "T4", "A4", null, 213573L, null)
        assertEquals("3:33", complexDuration.formattedDuration)
    }
}
