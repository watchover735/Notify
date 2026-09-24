package com.notify.core.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackStateUtilsTest {

    @Test
    fun testSanitizeDurationHandlesTimeUnsetAndNegativeValues() {
        assertEquals(0L, PlaybackStateUtils.sanitizeDuration(C.TIME_UNSET))
        assertEquals(0L, PlaybackStateUtils.sanitizeDuration(-100L))
        assertEquals(0L, PlaybackStateUtils.sanitizeDuration(0L))
        assertEquals(180_000L, PlaybackStateUtils.sanitizeDuration(180_000L))
    }

    @Test
    fun testSanitizePositionHandlesTimeUnsetAndClampsToDuration() {
        assertEquals(0L, PlaybackStateUtils.sanitizePosition(C.TIME_UNSET))
        assertEquals(0L, PlaybackStateUtils.sanitizePosition(-500L))
        assertEquals(50_000L, PlaybackStateUtils.sanitizePosition(50_000L, 100_000L))
        assertEquals(100_000L, PlaybackStateUtils.sanitizePosition(120_000L, 100_000L))
    }

    @Test
    fun testClampStartIndexWithEmptyQueueReturnsMinusOne() {
        assertEquals(-1, PlaybackStateUtils.clampStartIndex(queueSize = 0, requestedIndex = 0))
        assertEquals(-1, PlaybackStateUtils.clampStartIndex(queueSize = 0, requestedIndex = 5))
        assertEquals(-1, PlaybackStateUtils.clampStartIndex(queueSize = -1, requestedIndex = 2))
    }

    @Test
    fun testClampStartIndexClampsLowerAndUpperBounds() {
        assertEquals(0, PlaybackStateUtils.clampStartIndex(queueSize = 5, requestedIndex = -3))
        assertEquals(0, PlaybackStateUtils.clampStartIndex(queueSize = 5, requestedIndex = 0))
        assertEquals(2, PlaybackStateUtils.clampStartIndex(queueSize = 5, requestedIndex = 2))
        assertEquals(4, PlaybackStateUtils.clampStartIndex(queueSize = 5, requestedIndex = 4))
        assertEquals(4, PlaybackStateUtils.clampStartIndex(queueSize = 5, requestedIndex = 10))
    }

    @Test
    fun testSanitizeMediaItemIndexHandlesIndexUnsetAndOutOfBounds() {
        assertNull(PlaybackStateUtils.sanitizeMediaItemIndex(C.INDEX_UNSET, queueSize = 5))
        assertNull(PlaybackStateUtils.sanitizeMediaItemIndex(-2, queueSize = 5))
        assertNull(PlaybackStateUtils.sanitizeMediaItemIndex(5, queueSize = 5))
        assertNull(PlaybackStateUtils.sanitizeMediaItemIndex(0, queueSize = 0))
        assertEquals(2, PlaybackStateUtils.sanitizeMediaItemIndex(2, queueSize = 5))
    }

    @Test
    fun testCanSeekProtectsNonSeekableMediaAndOutOfBounds() {
        // Non-seekable media
        assertFalse(PlaybackStateUtils.canSeek(isSeekable = false, targetPositionMs = 10_000L, durationMs = 60_000L))

        // Negative target
        assertFalse(PlaybackStateUtils.canSeek(isSeekable = true, targetPositionMs = -1L, durationMs = 60_000L))

        // Target beyond duration
        assertFalse(PlaybackStateUtils.canSeek(isSeekable = true, targetPositionMs = 70_000L, durationMs = 60_000L))

        // Valid seek
        assertTrue(PlaybackStateUtils.canSeek(isSeekable = true, targetPositionMs = 30_000L, durationMs = 60_000L))
        assertTrue(PlaybackStateUtils.canSeek(isSeekable = true, targetPositionMs = 0L, durationMs = 60_000L))
    }
}
