package com.notify.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun `when current code is below min supported then returns HARD URGENT`() {
        val decision = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 18,
            latestCode = 22,
            minSupportedCode = 20,
            skipsUsed = 0,
            maxSkips = 3
        )

        assertTrue(decision is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.URGENT, (decision as UpdatePolicyDecision.Hard).reason)
    }

    @Test
    fun `when current code is between min supported and latest with skips remaining then returns SOFT`() {
        // Skips left = 3 - 0 = 3
        val decision0 = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 20,
            latestCode = 22,
            minSupportedCode = 19,
            skipsUsed = 0,
            maxSkips = 3
        )
        assertTrue(decision0 is UpdatePolicyDecision.Soft)
        assertEquals(3, (decision0 as UpdatePolicyDecision.Soft).skipsLeft)

        // Skips left = 3 - 2 = 1
        val decision2 = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 20,
            latestCode = 22,
            minSupportedCode = 19,
            skipsUsed = 2,
            maxSkips = 3
        )
        assertTrue(decision2 is UpdatePolicyDecision.Soft)
        assertEquals(1, (decision2 as UpdatePolicyDecision.Soft).skipsLeft)
    }

    @Test
    fun `when current code is below latest and skips used equals maxSkips then returns HARD SKIPS_EXHAUSTED`() {
        val decision = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 20,
            latestCode = 22,
            minSupportedCode = 19,
            skipsUsed = 3,
            maxSkips = 3
        )

        assertTrue(decision is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.SKIPS_EXHAUSTED, (decision as UpdatePolicyDecision.Hard).reason)
    }

    @Test
    fun `when current code is below latest and skips used exceeds maxSkips then returns HARD SKIPS_EXHAUSTED`() {
        val decision = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 20,
            latestCode = 22,
            minSupportedCode = 19,
            skipsUsed = 5,
            maxSkips = 3
        )

        assertTrue(decision is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.SKIPS_EXHAUSTED, (decision as UpdatePolicyDecision.Hard).reason)
    }

    @Test
    fun `when current code equals latest code then returns NONE`() {
        val decision = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 20,
            latestCode = 20,
            minSupportedCode = 19,
            skipsUsed = 0,
            maxSkips = 3
        )

        assertEquals(UpdatePolicyDecision.None, decision)
    }

    @Test
    fun `when current code is greater than latest code then returns NONE`() {
        val decision = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 25,
            latestCode = 20,
            minSupportedCode = 19,
            skipsUsed = 0,
            maxSkips = 3
        )

        assertEquals(UpdatePolicyDecision.None, decision)
    }

    @Test
    fun `when server is misconfigured with minSupported greater than latest then does not HARD URGENT block`() {
        // Misconfiguration: latest = 20, minSupported = 25 (typo by admin)
        // User on version 20 (up to date)
        val decisionOnLatest = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 20,
            latestCode = 20,
            minSupportedCode = 25,
            skipsUsed = 0,
            maxSkips = 3
        )
        // Must NOT be Hard
        assertEquals(UpdatePolicyDecision.None, decisionOnLatest)

        // User on version 19 with 0 skips used: should be Soft, not Hard URGENT
        val decisionBelowLatest = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = 19,
            latestCode = 20,
            minSupportedCode = 25,
            skipsUsed = 0,
            maxSkips = 3
        )
        assertTrue(decisionBelowLatest is UpdatePolicyDecision.Soft)
        assertEquals(3, (decisionBelowLatest as UpdatePolicyDecision.Soft).skipsLeft)
    }
}
