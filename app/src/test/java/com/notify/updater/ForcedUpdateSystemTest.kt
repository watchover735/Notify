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
        val currentCode = com.notify.BuildConfig.VERSION_CODE
        val urgentTarget = currentCode + 5
        val urgentConfig = AppConfig(
            latestVersionCode = urgentTarget,
            minSupportedVersionCode = urgentTarget,
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
        assertEquals(urgentTarget, hardState.config.minSupportedVersionCode)
        assertNull("Soft update must be null when hard update is active", viewModel.softUpdateState.value)
    }

    @Test
    fun `test soft update allows 3 skips before turning into hard block`() {
        val currentCode = com.notify.BuildConfig.VERSION_CODE
        val latestTarget = currentCode + 2
        val minTarget = (currentCode - 1).coerceAtLeast(1)
        val softConfig = AppConfig(
            latestVersionCode = latestTarget,
            minSupportedVersionCode = minTarget,
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

        // User taps "Later" (1st skip)
        viewModel.onDismissSoftUpdate()
        assertEquals(1, appConfigRepo.getSkipsUsed(latestTarget))
        assertNull("Dismissed soft update dialog must be null for current session", viewModel.softUpdateState.value)

        // 2nd skip simulation:
        appConfigRepo.incrementSkips(latestTarget) // skips = 2
        assertEquals(2, appConfigRepo.getSkipsUsed(latestTarget))

        // 3rd skip simulation:
        appConfigRepo.incrementSkips(latestTarget) // skips = 3 (exhausted)
        assertEquals(3, appConfigRepo.getSkipsUsed(latestTarget))

        // Re-evaluating now must yield HARD(SKIPS_EXHAUSTED)
        val (decision, config) = appConfigRepo.evaluatePolicy(softConfig, currentCode = currentCode)
        assertTrue(decision is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.SKIPS_EXHAUSTED, (decision as UpdatePolicyDecision.Hard).reason)
    }

    @Test
    fun `test up to date app has neither hard nor soft update state`() {
        val currentCode = com.notify.BuildConfig.VERSION_CODE
        val currentConfig = AppConfig(
            latestVersionCode = currentCode,
            minSupportedVersionCode = (currentCode - 1).coerceAtLeast(1),
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
