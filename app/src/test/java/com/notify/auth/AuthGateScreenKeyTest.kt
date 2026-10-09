package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests verifying that AuthGateScreenKey mapping:
 * 1. Remains stable when internal screen parameters (isLoading, error, message) change.
 * 2. Produces distinct keys when transitioning between actual functional screens.
 * 3. Correctly handles NeedEmailOtp as an isolated screen key.
 */
class AuthGateScreenKeyTest {

    @Test
    fun needLogin_screenKeyRemainsStableAcrossLoadingAndError() {
        val initial = AuthGateState.NeedLogin(error = null, isLoading = false)
        val loading = AuthGateState.NeedLogin(error = null, isLoading = true)
        val withError = AuthGateState.NeedLogin(error = "Invalid email or password", isLoading = false)
        val loadingWithError = AuthGateState.NeedLogin(error = "Some error", isLoading = true)

        assertEquals(AuthGateScreenKey.NeedLogin, initial.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedLogin, loading.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedLogin, withError.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedLogin, loadingWithError.toScreenKey())
    }

    @Test
    fun needEmailOtp_screenKeyRemainsStableAcrossLoadingErrorAndMessage() {
        val email = "listener@example.com"
        val initial = AuthGateState.NeedEmailOtp(email = email, error = null, message = null, isLoading = false)
        val loading = AuthGateState.NeedEmailOtp(email = email, error = null, message = null, isLoading = true)
        val withError = AuthGateState.NeedEmailOtp(email = email, error = "Code is invalid or expired", message = null, isLoading = false)
        val withMessage = AuthGateState.NeedEmailOtp(email = email, error = null, message = "Code has been resent", isLoading = false)

        assertEquals(AuthGateScreenKey.NeedEmailOtp, initial.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedEmailOtp, loading.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedEmailOtp, withError.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedEmailOtp, withMessage.toScreenKey())
    }

    @Test
    fun needNickname_screenKeyRemainsStableAcrossLoadingAndError() {
        val initial = AuthGateState.NeedNickname(error = null, isLoading = false)
        val loading = AuthGateState.NeedNickname(error = null, isLoading = true)
        val withError = AuthGateState.NeedNickname(error = "2-20 characters required", isLoading = false)

        assertEquals(AuthGateScreenKey.NeedNickname, initial.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedNickname, loading.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedNickname, withError.toScreenKey())
    }

    @Test
    fun needKey_screenKeyRemainsStableAcrossLoadingMessageAndError() {
        val initial = AuthGateState.NeedKey(message = null, isError = false, isLoading = false)
        val loading = AuthGateState.NeedKey(message = "Verifying...", isError = false, isLoading = true)
        val withError = AuthGateState.NeedKey(message = "Key invalid hai", isError = true, isLoading = false)

        assertEquals(AuthGateScreenKey.NeedKey, initial.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedKey, loading.toScreenKey())
        assertEquals(AuthGateScreenKey.NeedKey, withError.toScreenKey())
    }

    @Test
    fun distinctScreens_produceDistinctScreenKeys() {
        val loading = AuthGateState.Loading.toScreenKey()
        val login = AuthGateState.NeedLogin().toScreenKey()
        val otp = AuthGateState.NeedEmailOtp(email = "user@test.com").toScreenKey()
        val nickname = AuthGateState.NeedNickname().toScreenKey()
        val key = AuthGateState.NeedKey().toScreenKey()
        val ready = AuthGateState.Ready.toScreenKey()
        val error = AuthGateState.Error(message = "Connection failed", onRetry = {}).toScreenKey()

        val allKeys = listOf(loading, login, otp, nickname, key, ready, error)
        val distinctKeys = allKeys.toSet()

        // Every screen variant must map to a unique AuthGateScreenKey
        assertEquals("All 7 gate screens must map to distinct keys", 7, distinctKeys.size)

        // Explicit boundary assertions
        assertNotEquals(login, otp)
        assertNotEquals(otp, nickname)
        assertNotEquals(nickname, key)
        assertNotEquals(key, ready)
        assertNotEquals(login, ready)
        assertNotEquals(loading, login)
    }
}
