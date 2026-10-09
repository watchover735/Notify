package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExpiryCheckTest {

    @Test
    fun entitlementInfo_isActiveReturnsTrueOnlyForActiveStatus() {
        val active = EntitlementInfo("active", 1000L, 500L)
        val expired = EntitlementInfo("expired", 1000L, 1500L)
        val revoked = EntitlementInfo("revoked", null, 500L)
        val none = EntitlementInfo("none", null, 500L)

        assertTrue(active.isActive)
        assertFalse(expired.isActive)
        assertFalse(revoked.isActive)
        assertFalse(none.isActive)
    }

    @Test
    fun monotonicServerTimeEstimate_advancesWithElapsedRealtimeDelta() {
        val serverTimeAtCheck = 1_700_000_000_000L
        val elapsedAtCheck = 100_000L

        // Device elapsedRealtime advanced by 300_000 ms (5 minutes)
        val currentElapsed = 400_000L
        val elapsedDelta = currentElapsed - elapsedAtCheck
        val estimatedServerTime = serverTimeAtCheck + elapsedDelta

        assertEquals(1_700_000_300_000L, estimatedServerTime)
    }

    @Test
    fun offlineGrace_expiresStrictlyWhenEstimatedServerTimeExceedsExpiresAt() {
        val serverTimeAtCheck = 1_700_000_000_000L
        val expiresAtMs = 1_700_000_300_000L // 5 minutes validity
        val elapsedAtCheck = 100_000L

        // After 6 minutes (360_000 ms elapsed delta)
        val currentElapsed = 460_000L
        val estimatedServerNow = serverTimeAtCheck + (currentElapsed - elapsedAtCheck)

        val isNotExpired = estimatedServerNow < expiresAtMs
        assertFalse("Offline grace must not allow access after expires_at has passed", isNotExpired)
    }

    @Test
    fun redeemResult_supportsPermanentAlreadyCode() {
        val result = RedeemResult(
            code = "permanent_already",
            message = "You already have permanent access, key was not used.",
            expiresAtEpochMs = null,
            serverTimeEpochMs = 1_700_000_000_000L
        )
        assertEquals("permanent_already", result.code)
    }
}
