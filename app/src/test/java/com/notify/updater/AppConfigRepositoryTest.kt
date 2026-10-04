package com.notify.updater

import android.content.Context
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
class AppConfigRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: AppConfigRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("notify_app_config_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = AppConfigRepository(context)
    }

    @Test
    fun `when cache is empty and offline then returns None with null config`() {
        assertNull(repository.getCachedConfig())

        val (decision, config) = repository.evaluatePolicy(null, currentCode = 20)
        assertEquals(UpdatePolicyDecision.None, decision)
        assertNull(config)
    }

    @Test
    fun `test cache save and retrieve`() {
        val testConfig = AppConfig(
            latestVersionCode = 22,
            minSupportedVersionCode = 20,
            maxSkips = 3,
            forceMessage = "Test message",
            downloadUrl = "https://example.com/download"
        )

        repository.saveCachedConfig(testConfig)

        val cached = repository.getCachedConfig()
        assertNotNull(cached)
        assertEquals(22, cached!!.latestVersionCode)
        assertEquals(20, cached.minSupportedVersionCode)
        assertEquals(3, cached.maxSkips)
        assertEquals("Test message", cached.forceMessage)
        assertEquals("https://example.com/download", cached.downloadUrl)
    }

    @Test
    fun `test skips tracking per target version code`() {
        // Skips start at 0 for version 21
        assertEquals(0, repository.getSkipsUsed(21))

        repository.incrementSkips(21)
        assertEquals(1, repository.getSkipsUsed(21))

        repository.incrementSkips(21)
        assertEquals(2, repository.getSkipsUsed(21))

        // When a new version 22 comes, its skips are 0 automatically
        assertEquals(0, repository.getSkipsUsed(22))
    }

    @Test
    fun `when evaluatePolicy with cached config and skips used below maxSkips then returns Soft`() {
        val config = AppConfig(
            latestVersionCode = 22,
            minSupportedVersionCode = 19,
            maxSkips = 3,
            forceMessage = "Update please"
        )
        repository.saveCachedConfig(config)

        // 0 skips used out of 3 -> Soft(3)
        val (decision1, _) = repository.evaluatePolicy(null, currentCode = 20)
        assertTrue(decision1 is UpdatePolicyDecision.Soft)
        assertEquals(3, (decision1 as UpdatePolicyDecision.Soft).skipsLeft)

        // Increment 3 times
        repository.incrementSkips(22)
        repository.incrementSkips(22)
        repository.incrementSkips(22)

        // 3 skips used -> Hard(SKIPS_EXHAUSTED)
        val (decision2, _) = repository.evaluatePolicy(null, currentCode = 20)
        assertTrue(decision2 is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.SKIPS_EXHAUSTED, (decision2 as UpdatePolicyDecision.Hard).reason)
    }

    @Test
    fun `when evaluatePolicy with current code below minSupportedCode then returns Hard URGENT`() {
        val config = AppConfig(
            latestVersionCode = 25,
            minSupportedVersionCode = 22,
            maxSkips = 3,
            forceMessage = "Urgent update required"
        )
        repository.saveCachedConfig(config)

        val (decision, _) = repository.evaluatePolicy(null, currentCode = 20)
        assertTrue(decision is UpdatePolicyDecision.Hard)
        assertEquals(HardUpdateReason.URGENT, (decision as UpdatePolicyDecision.Hard).reason)
    }
}
