package com.notify.auth

/**
 * Represents an active authenticated session with Supabase.
 */
data class SupabaseAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
    val isOffline: Boolean = false
) {
    fun isExpired(bufferSeconds: Long = 60L): Boolean {
        if (expiresAtEpochSec <= 0L) return false
        val nowSec = System.currentTimeMillis() / 1000L
        return nowSec + bufferSeconds >= expiresAtEpochSec
    }
}
