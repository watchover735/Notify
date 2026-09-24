package com.notify.ui.player

import com.notify.core.playback.PlaybackStateUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {

    @Test
    fun testZeroMilliseconds() {
        assertEquals("0:00", PlaybackStateUtils.formatDurationMs(0L))
    }

    @Test
    fun testSecondsAndMinutes() {
        assertEquals("0:09", PlaybackStateUtils.formatDurationMs(9_000L))
        assertEquals("1:05", PlaybackStateUtils.formatDurationMs(65_000L))
        assertEquals("3:45", PlaybackStateUtils.formatDurationMs(225_000L))
    }

    @Test
    fun testHoursMinutesSeconds() {
        assertEquals("1:01:05", PlaybackStateUtils.formatDurationMs(3_665_000L))
        assertEquals("2:30:15", PlaybackStateUtils.formatDurationMs(9_015_000L))
    }

    @Test
    fun testNegativeAndUnsetDuration() {
        assertEquals("0:00", PlaybackStateUtils.formatDurationMs(-1L))
        assertEquals("0:00", PlaybackStateUtils.formatDurationMs(-5000L))
    }
}
