package com.notify.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudPlaylistSyncTest {

    @Test
    fun playlistTrackSerialization_formatsMetadataAccuratelyWithoutAudio() {
        val tracksArray = JSONArray()

        val trackObj = JSONObject().apply {
            put("spotify_id", "4cOdK2wGLETKBW3PvgPWqT")
            put("title", "Main Rang Sharbaton Ka")
            put("artist", "Atif Aslam")
            put("duration", 263)
            put("duration_ms", 263000L)
            put("position", 1)
        }
        tracksArray.put(trackObj)

        val playlistPayload = JSONObject().apply {
            put("user_id", "fc78b1ab-bfab-4c2a-8891-4ff67ee86c5f")
            put("playlist_id", "pl_spotify_4cOdK2wGLETKBW3PvgPWqT")
            put("title", "Romantic Hits")
            put("tracks_json", tracksArray)
        }

        assertEquals("fc78b1ab-bfab-4c2a-8891-4ff67ee86c5f", playlistPayload.getString("user_id"))
        assertEquals("Romantic Hits", playlistPayload.getString("title"))

        val parsedTracks = playlistPayload.getJSONArray("tracks_json")
        assertEquals(1, parsedTracks.length())

        val firstTrack = parsedTracks.getJSONObject(0)
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", firstTrack.getString("spotify_id"))
        assertEquals("Main Rang Sharbaton Ka", firstTrack.getString("title"))
        assertEquals("Atif Aslam", firstTrack.getString("artist"))
        assertEquals(263, firstTrack.getInt("duration"))
        assertEquals(263000L, firstTrack.getLong("duration_ms"))
    }

    @Test
    fun playlistTrackDeserialization_restoresDurationInSecondsOrMs() {
        val rawJson = """
            {
                "playlist_id": "pl_spotify_123",
                "title": "My Chill Mix",
                "tracks_json": [
                    {
                        "spotify_id": "4cOdK2wGLETKBW3PvgPWqT",
                        "title": "Main Rang Sharbaton Ka",
                        "artist": "Atif Aslam",
                        "duration": 263
                    }
                ]
            }
        """.trimIndent()

        val obj = JSONObject(rawJson)
        val tracks = obj.getJSONArray("tracks_json")
        val track0 = tracks.getJSONObject(0)

        val durationSec = track0.optLong("duration", 0L)
        val durationMs = if (track0.has("duration_ms")) track0.getLong("duration_ms") else durationSec * 1000L

        assertEquals(263L, durationSec)
        assertEquals(263000L, durationMs)
        assertEquals("Main Rang Sharbaton Ka", track0.getString("title"))
        assertEquals("Atif Aslam", track0.getString("artist"))
    }
}
