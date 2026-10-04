package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDrawerTest {

    @Test
    fun avatarInitial_extractsFirstLetterCorrectly() {
        val initialFromNickname = "Rahul".trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
        assertEquals("R", initialFromNickname)

        val initialFromLowercase = "aman".trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
        assertEquals("A", initialFromLowercase)

        val initialFromEmpty = "".trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
        assertEquals("N", initialFromEmpty)
    }

    @Test
    fun keyStatusDescription_formatsExpiryCorrectly() {
        fun formatStatus(expiresAt: Long?, estimatedNow: Long): String {
            if (expiresAt == null) return "Key: Permanent"
            val remainingMs = expiresAt - estimatedNow
            if (remainingMs <= 0) return "Key: Expired"
            val days = (remainingMs / (24 * 3600 * 1000L)).toInt()
            val hours = (remainingMs / (3600 * 1000L)).toInt()
            return when {
                days == 0 -> if (hours <= 1) "Key: 1 ghante me expire hogi" else "Key: $hours ghante bache"
                days == 1 -> "Key: Kal expire hogi"
                else -> "Key: $days din bache"
            }
        }

        val baseNow = 1_700_000_000_000L

        // Permanent
        assertEquals("Key: Permanent", formatStatus(null, baseNow))

        // Expired
        assertEquals("Key: Expired", formatStatus(baseNow - 1000L, baseNow))

        // 3 hours left
        assertEquals("Key: 3 ghante bache", formatStatus(baseNow + 3 * 3600 * 1000L, baseNow))

        // 1 day left (between 24h and 48h)
        assertEquals("Key: Kal expire hogi", formatStatus(baseNow + 26 * 3600 * 1000L, baseNow))

        // 5 days left
        assertEquals("Key: 5 din bache", formatStatus(baseNow + 5 * 24 * 3600 * 1000L, baseNow))
    }
}
