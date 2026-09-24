package com.notify.core.local

import com.notify.core.model.AudioSource
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ensures timestamp values stored from MediaStore DATE_MODIFIED preserve Unix epoch seconds
 * and that conversions to epoch milliseconds are mathematically exact without seconds/milliseconds confusion.
 */
class LocalAudioTimestampTest {

    @Test
    fun testEpochSecondsVersusMillisecondsIntegrity() {
        // MediaStore DATE_MODIFIED returns seconds (e.g. 1710325000L representing March 2024)
        val sampleEpochSeconds = 1710325000L
        val expectedEpochMs = 1710325000000L

        val item = LocalAudioItem(
            track = Track(
                id = TrackId.local("content://media/1"),
                title = "Test",
                artist = "Test",
                source = AudioSource.Local("content://media/1")
            ),
            sizeBytes = 1024L,
            mimeType = "audio/mp3",
            dateModifiedEpochSeconds = sampleEpochSeconds
        )

        // Verify stored value is in seconds (< 2_000_000_000L until year 2033)
        assertEquals(sampleEpochSeconds, item.dateModifiedEpochSeconds)
        assertTrue("Epoch seconds should be less than 2e9 until year 2033", item.dateModifiedEpochSeconds < 2_000_000_000L)

        // Verify computed millisecond conversion
        assertEquals(expectedEpochMs, item.dateModifiedEpochMs)
        assertTrue("Epoch milliseconds should be greater than 1e12 for modern dates", item.dateModifiedEpochMs > 1_000_000_000_000L)
    }

    @Test
    fun testZeroTimestampPreserved() {
        val item = LocalAudioItem(
            track = Track(
                id = TrackId.local("content://media/2"),
                title = "Zero",
                artist = "Zero",
                source = AudioSource.Local("content://media/2")
            ),
            sizeBytes = 0L,
            mimeType = null,
            dateModifiedEpochSeconds = 0L
        )

        assertEquals(0L, item.dateModifiedEpochSeconds)
        assertEquals(0L, item.dateModifiedEpochMs)
    }
}
