package com.notify.ui.library

import com.notify.core.local.LocalAudioItem
import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LibrarySortFilterTest {

    private lateinit var sampleItems: List<LocalAudioItem>

    @Before
    fun setup() {
        sampleItems = listOf(
            createItem("1", "Zebra", "Beta Artist", "Album C", 120000L, 100L),
            createItem("2", "Apple", "Alpha Artist", "Album A", 300000L, 300L),
            createItem("3", "Mango", "Gamma Artist", "Album B", 200000L, 200L)
        )
    }

    @Test
    fun testSortByTitleAscendingAndDescending() {
        val asc = LibrarySortFilter.sortAndFilter(sampleItems, sortOption = TrackSortOption.TITLE, sortDirection = SortDirection.ASCENDING)
        assertEquals("Apple", asc[0].track.title)
        assertEquals("Mango", asc[1].track.title)
        assertEquals("Zebra", asc[2].track.title)

        val desc = LibrarySortFilter.sortAndFilter(sampleItems, sortOption = TrackSortOption.TITLE, sortDirection = SortDirection.DESCENDING)
        assertEquals("Zebra", desc[0].track.title)
        assertEquals("Mango", desc[1].track.title)
        assertEquals("Apple", desc[2].track.title)
    }

    @Test
    fun testSortByArtistAscending() {
        val result = LibrarySortFilter.sortAndFilter(sampleItems, sortOption = TrackSortOption.ARTIST, sortDirection = SortDirection.ASCENDING)
        assertEquals("Alpha Artist", result[0].track.artist)
        assertEquals("Beta Artist", result[1].track.artist)
        assertEquals("Gamma Artist", result[2].track.artist)
    }

    @Test
    fun testSortByAlbumAscending() {
        val result = LibrarySortFilter.sortAndFilter(sampleItems, sortOption = TrackSortOption.ALBUM, sortDirection = SortDirection.ASCENDING)
        assertEquals("Album A", result[0].track.album)
        assertEquals("Album B", result[1].track.album)
        assertEquals("Album C", result[2].track.album)
    }

    @Test
    fun testSortByDurationAscending() {
        val result = LibrarySortFilter.sortAndFilter(sampleItems, sortOption = TrackSortOption.DURATION, sortDirection = SortDirection.ASCENDING)
        assertEquals(120000L, result[0].track.durationMs)
        assertEquals(200000L, result[1].track.durationMs)
        assertEquals(300000L, result[2].track.durationMs)
    }

    @Test
    fun testSortByDateModifiedDescending() {
        val result = LibrarySortFilter.sortAndFilter(sampleItems, sortOption = TrackSortOption.DATE_MODIFIED, sortDirection = SortDirection.DESCENDING)
        assertEquals(300L, result[0].dateModifiedEpochSeconds)
        assertEquals(200L, result[1].dateModifiedEpochSeconds)
        assertEquals(100L, result[2].dateModifiedEpochSeconds)
    }

    @Test
    fun testFilterAndSortCombined() {
        val result = LibrarySortFilter.sortAndFilter(
            items = sampleItems,
            query = "Artist",
            sortOption = TrackSortOption.DURATION,
            sortDirection = SortDirection.DESCENDING
        )
        assertEquals(3, result.size)
        assertEquals(300000L, result[0].track.durationMs)
        assertEquals(120000L, result[2].track.durationMs)
    }

    private fun createItem(
        rawId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        dateModifiedEpochSeconds: Long
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
            sizeBytes = 4_000_000L,
            mimeType = "audio/mpeg",
            dateModifiedEpochSeconds = dateModifiedEpochSeconds
        )
    }
}
