package com.notify.core.local

import com.notify.core.model.AudioSource
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioDeduplicationTest {

    private fun createItem(uriString: String, title: String): LocalAudioItem {
        return LocalAudioItem(
            track = Track(
                id = TrackId.local(uriString),
                title = title,
                artist = "Artist",
                source = AudioSource.Local(uriString)
            ),
            sizeBytes = 1000L,
            mimeType = "audio/mp3",
            dateModifiedEpochSeconds = 1700000000L
        )
    }

    @Test
    fun testExactDuplicateUriFiltered() {
        val item1 = createItem("content://media/external/audio/media/1", "Track 1")
        val item2 = createItem("content://media/external/audio/media/1", "Track 1 Duplicate")
        val item3 = createItem("content://media/external/audio/media/2", "Track 2")

        val result = DefaultLocalAudioRepository.deduplicateItems(listOf(item1, item2, item3))

        assertEquals(2, result.size)
        assertEquals("Track 1", result[0].track.title)
        assertEquals("Track 2", result[1].track.title)
    }

    @Test
    fun testNormalizedUriDuplicateFiltered() {
        val item1 = createItem("content://media/external/audio/media/1", "Track 1 Lower")
        val item2 = createItem("CONTENT://MEDIA/EXTERNAL/AUDIO/MEDIA/1", "Track 1 Upper")
        val item3 = createItem(" content://media/external/audio/media/1 ", "Track 1 Whitespace")

        val result = DefaultLocalAudioRepository.deduplicateItems(listOf(item1, item2, item3))

        assertEquals(1, result.size)
        assertEquals("Track 1 Lower", result[0].track.title)
    }

    @Test
    fun testDistinctUrisPreserved() {
        val item1 = createItem("content://media/external/audio/media/1", "Track 1")
        val item2 = createItem("content://media/external/audio/media/2", "Track 2")
        val item3 = createItem("content://com.android.providers.downloads.documents/document/raw%3A3", "SAF Track 3")

        val result = DefaultLocalAudioRepository.deduplicateItems(listOf(item1, item2, item3))

        assertEquals(3, result.size)
    }
}
