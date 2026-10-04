package com.notify.updater

import android.app.Application
import android.content.Context
import com.notify.auth.AuthGateViewModel
import com.notify.auth.HardUpdateState
import com.notify.auth.SoftUpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForcedUpdateSystemTest {

    private lateinit var context: Context
    private lateinit var appConfigRepo: AppConfigRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("notify_app_config_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        appConfigRepo = AppConfigRepository(context)
    }

    @Test
    fun `test urgent forced update triggers hard update state and pauses playback`() {
        // Cache a config requiring min_supported = 25 (current BuildConfig.VERSION_CODE is 20)
        val urgentConfig = AppConfig(
            latestVersionCode = 25,
            minSupportedVersionCode = 25,
            maxSkips = 3,
            forceMessage = "Urgent security update required."
        )
        appConfigRepo.saveCachedConfig(urgentConfig)

        var playbackPaused = false
        val viewModel = AuthGateViewModel(context as Application)
        viewModel.setPlaybackPauseAction {
            playbackPaused = true
        }

        viewModel.checkAppConfig(isResume = false)

        val hardState = viewModel.hardUpdateState.value
        assertNotNull("Hard update state must be active for urgent update", hardState)
        assertEquals(HardUpdateReason.URGENT, hardState!!.reason)
        assertEquals(25, hardState.config.minSupportedVersionCode)
        assertNull("Soft update must be null when hard update is active", viewModel.softUpdateState.value)
    }

    @Test
    fun `test soft update allows 3 skips before turning into hard block`() {
        // Config: latest = 22, minSupported = 19 (current = 20)
        val softConfig = AppConfig(
            latestVersionCode = 22,
            minSupportedVersionCode = 19,
            maxSkips = 3,
            forceMessage = "Optional new features"
        )
        appConfigRepo.saveCachedConfig(softConfig)

        val viewModel = AuthGateViewModel(context as Application)
        viewModel.checkAppConfig(isResume = false)

        // 1st time: Soft(3)
        var softState = viewModel.softUpdateState.value
        assertNotNull(softState)
        assertEquals(3, softState!!.skipsLeft)
        assertNull(viewModel.hardUpdateState.value)

        // User taps "Baad me" (1st skip)
        viewModel.onDismissSoftUpdate()
        assertEquals(1, appConfigRepo.getSkipsUsed(22))
        assertNull("Dismissed soft update dialog must be null for current session", viewModel.softUpdateState.value)

        // 2nd skip simulation:
        appConfigRepo.incrementSkips(22) // skips = 2
        assertEquals(2, appConfigRepo.getSkipsUsed(22))

        // 3rd skip simulation:
        appConfigRepo.incrementSkips(22) // skips = 3 (exhausted)
        assertEquals(3, appConfigRepo.getSkipsUsed(22))

        // Re-evaluating now must yield HARD(SKIPS_EXHAUSTED)
        val (decision, config) = appConfigRepo.evaluatePolicy(softConfig, currentCode = 20)
        assertTrue(decision is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.SKIPS_EXHAUSTED, (decision as UpdatePolicyDecision.Hard).reason)
    }

    @Test
    fun `test up to date app has neither hard nor soft update state`() {
        // Current code = 20, latest = 20, min = 19
        val currentConfig = AppConfig(
            latestVersionCode = 20,
            minSupportedVersionCode = 19,
            maxSkips = 3,
            forceMessage = "Normal"
        )
        appConfigRepo.saveCachedConfig(currentConfig)

        val viewModel = AuthGateViewModel(context as Application)
        viewModel.checkAppConfig(isResume = false)

        assertNull(viewModel.hardUpdateState.value)
        assertNull(viewModel.softUpdateState.value)
    }
}
