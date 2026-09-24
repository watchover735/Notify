package com.notify.ui.search

import com.notify.core.local.LocalAudioItem
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalSearchFilterTest {

    private lateinit var sampleItems: List<LocalAudioItem>

    @Before
    fun setup() {
        sampleItems = listOf(
            createItem("1", "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354000L),
            createItem("2", "Hotel California", "Eagles", "Hotel California", 390000L),
            createItem("3", "Stairway to Heaven", "Led Zeppelin", "Led Zeppelin IV", 482000L),
            createItem("4", "Another One Bites the Dust", "Queen", "The Game", 215000L)
        )
    }

    @Test
    fun testEmptyQueryReturnsEmptyList() {
        val result = LocalSearchFilter.filter(sampleItems, "")
        assertTrue(result.isEmpty())

        val whitespaceResult = LocalSearchFilter.filter(sampleItems, "   ")
        assertTrue(whitespaceResult.isEmpty())
    }

    @Test
    fun testSearchByTitleMatches() {
        val result = LocalSearchFilter.filter(sampleItems, "bohemian")
        assertEquals(1, result.size)
        assertEquals("Bohemian Rhapsody", result[0].track.title)
    }

    @Test
    fun testSearchByArtistMatchesMultipleTracks() {
        val result = LocalSearchFilter.filter(sampleItems, "queen")
        assertEquals(2, result.size)
        assertTrue(result.any { it.track.title == "Bohemian Rhapsody" })
        assertTrue(result.any { it.track.title == "Another One Bites the Dust" })
    }

    @Test
    fun testSearchByAlbumMatches() {
        val result = LocalSearchFilter.filter(sampleItems, "Opera")
        assertEquals(1, result.size)
        assertEquals("A Night at the Opera", result[0].track.album)
    }

    @Test
    fun testSearchCaseInsensitive() {
        val lower = LocalSearchFilter.filter(sampleItems, "eagles")
        val upper = LocalSearchFilter.filter(sampleItems, "EAGLES")
        assertEquals(1, lower.size)
        assertEquals(lower.size, upper.size)
        assertEquals(lower[0].track.id, upper[0].track.id)
    }

    @Test
    fun testNoMatchesReturnsEmptyList() {
        val result = LocalSearchFilter.filter(sampleItems, "NonExistentTerm123")
        assertTrue(result.isEmpty())
    }

    private fun createItem(
        rawId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LocalAudioItem {
        return LocalAudioItem(
            track = Track(
                id = TrackId(ProviderId.LOCAL, "content://media/$rawId"),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                source = AudioSource.Local("content://media/$rawId")
            ),
            sizeBytes = 5_000_000L,
            mimeType = "audio/mpeg",
            dateModifiedEpochSeconds = 1700000000L
        )
    }
}
