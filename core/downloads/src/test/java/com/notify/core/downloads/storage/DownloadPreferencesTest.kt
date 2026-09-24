package com.notify.core.downloads.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DownloadPreferencesTest {

    private lateinit var context: Context
    private lateinit var prefs: DownloadPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = DownloadPreferences(context)
    }

    @Test
    fun defaultPreferences_haveSensibleValues() {
        assertEquals(DownloadPreferences.DEFAULT_SMART_BUDGET_BYTES, prefs.smartBudgetBytes)
        assertEquals(1024L * 1024L * 1024L, prefs.smartBudgetBytes)
        assertEquals(DownloadPreferences.DEFAULT_FREE_SPACE_RESERVE_BYTES, prefs.freeSpaceReserveBytes)
        assertEquals(30_000L, prefs.autoSaveThresholdMs)
        assertTrue(prefs.autoSaveEnabled)
        assertEquals(AudioQualityProfile.NORMAL, prefs.audioQualityProfile)
    }

    @Test
    fun preferences_arePersisted() {
        prefs.smartBudgetBytes = 500L * 1024L * 1024L
        prefs.audioQualityProfile = AudioQualityProfile.HIGH
        prefs.autoSaveEnabled = false
        prefs.wifiOnly = true

        val reloaded = DownloadPreferences(context)
        assertEquals(500L * 1024L * 1024L, reloaded.smartBudgetBytes)
        assertEquals(AudioQualityProfile.HIGH, reloaded.audioQualityProfile)
        assertEquals(false, reloaded.autoSaveEnabled)
        assertEquals(true, reloaded.wifiOnly)
    }
}
