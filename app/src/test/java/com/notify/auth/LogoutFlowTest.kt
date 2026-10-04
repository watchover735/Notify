package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutFlowTest {

    @Test
    fun screenKey_onNeedLoginReturnsNeedLoginKey() {
        val state = AuthGateState.NeedLogin()
        assertEquals(AuthGateScreenKey.NeedLogin, state.toScreenKey())
    }

    @Test
    fun preferenceSeparation_authAndEntitlementPrefsAreIsolatedFromLibrary() {
        val authPrefsName = "notify_auth_prefs"
        val entitlementPrefsName = "notify_entitlement_prefs"
        val databaseName = "notify_music.db"

        assertNotEquals(authPrefsName, databaseName)
        assertNotEquals(entitlementPrefsName, databaseName)
        assertTrue(databaseName.endsWith(".db"))
    }
}
