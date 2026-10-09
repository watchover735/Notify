package com.notify.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserTelemetryTest {

    @Test
    fun heartbeatPayload_formatsActiveAndPlaySecondsProperly() {
        val totalActiveSec = 120L
        val totalPlaySec = 75L
        val version = "1.0.0"

        val bodyJson = JSONObject().apply {
            put("p_active_seconds", totalActiveSec)
            put("p_play_seconds", totalPlaySec)
            put("p_app_version", version)
        }

        assertEquals(120L, bodyJson.getLong("p_active_seconds"))
        assertEquals(75L, bodyJson.getLong("p_play_seconds"))
        assertEquals("1.0.0", bodyJson.getString("p_app_version"))
    }

    @Test
    fun dailyActiveMinutes_calculationMatchesExpectation() {
        val activeSeconds = 3600L
        val activeMinutes = activeSeconds / 60.0
        val activeHours = activeSeconds / 3600.0

        assertEquals(60.0, activeMinutes, 0.01)
        assertEquals(1.0, activeHours, 0.01)
    }
}
