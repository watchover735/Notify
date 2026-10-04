package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyUpdateTest {

    @Test
    fun keyNormalization_cleansHyphensSpacesAndConvertsUppercase() {
        val rawInput = "  notify- 2026- test- key  "
        val normalized = rawInput.trim().uppercase().replace("\\s+".toRegex(), "")
        assertEquals("NOTIFY-2026-TEST-KEY", normalized)

        val spacedKey = "abc def  ghi "
        val cleaned = spacedKey.trim().uppercase().replace("\\s+".toRegex(), "")
        assertEquals("ABCDEFGHI", cleaned)
    }

    @Test
    fun redeemResultCodes_containExpectedStatuses() {
        val validCodes = setOf("ok", "permanent_already", "invalid", "already_used", "revoked", "too_many_attempts")

        val resultOk = RedeemResult("ok", "Key successfully redeemed", 1_800_000_000_000L, 1_700_000_000_000L)
        val resultPerm = RedeemResult("permanent_already", "Aapke paas pehle se permanent access hai", null, 1_700_000_000_000L)

        assert(validCodes.contains(resultOk.code))
        assert(validCodes.contains(resultPerm.code))
        assertEquals("permanent_already", resultPerm.code)
    }
}
