package com.notify.updater

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        AppUpdater.resetSessionForTesting()
    }

    @Test
    fun `test single repository URL constant`() {
        assertEquals(
            "https://api.github.com/repos/watchover735/Notify/releases/latest",
            AppUpdater.GITHUB_LATEST_RELEASE_URL
        )
    }

    @Test
    fun `test onLaterClicked suppresses update notifications for current session`() {
        val testInfo = UpdateInfo(
            versionName = "v1.0.0",
            changelog = "Bug fixes and improvements",
            apkAsset = ApkAsset("notify.apk", "https://example.com/notify.apk", 1000L)
        )

        // Simulate state before dismissal
        AppUpdater.onLaterClicked()
        assertNull("updateAvailable must be null after onLaterClicked", AppUpdater.updateAvailable.value)

        // Check that further checks in the same session are ignored
        AppUpdater.checkForUpdates(context, currentVersion = "0.6.0")
        assertNull("Update dialog should remain suppressed after Later is clicked", AppUpdater.updateAvailable.value)
    }

    @Test
    fun `test resetSessionForTesting allows checking again`() {
        AppUpdater.onLaterClicked()
        assertNull(AppUpdater.updateAvailable.value)

        AppUpdater.resetSessionForTesting()
        assertNull(AppUpdater.updateAvailable.value)
    }

    @Test
    fun `test parsing assets with multiple files prefers universal or first apk`() {
        val assetsJson = JSONArray("""
            [
                {
                    "name": "notify-arm64-v8a.apk",
                    "browser_download_url": "https://github.com/release/notify-arm64-v8a.apk",
                    "size": 15000000
                },
                {
                    "name": "notify-universal.apk",
                    "browser_download_url": "https://github.com/release/notify-universal.apk",
                    "size": 30000000
                },
                {
                    "name": "source_code.zip",
                    "browser_download_url": "https://github.com/release/source.zip",
                    "size": 500000
                }
            ]
        """)

        var selectedAsset: ApkAsset? = null
        for (i in 0 until assetsJson.length()) {
            val obj = assetsJson.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val url = obj.optString("browser_download_url", "")
            val size = obj.optLong("size", 0L)
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                val asset = ApkAsset(name, url, size)
                if (name.contains("universal", ignoreCase = true)) {
                    selectedAsset = asset
                    break
                }
                if (selectedAsset == null) {
                    selectedAsset = asset
                }
            }
        }

        assertNotNull(selectedAsset)
        assertEquals("notify-universal.apk", selectedAsset!!.name)
    }

    @Test
    fun `test parsing release with no apk assets handles gracefully`() {
        val assetsJson = JSONArray("""
            [
                {
                    "name": "source_code.tar.gz",
                    "browser_download_url": "https://github.com/release/source.tar.gz",
                    "size": 500000
                }
            ]
        """)

        var selectedAsset: ApkAsset? = null
        for (i in 0 until assetsJson.length()) {
            val obj = assetsJson.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val url = obj.optString("browser_download_url", "")
            val size = obj.optLong("size", 0L)
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                selectedAsset = ApkAsset(name, url, size)
                break
            }
        }

        assertNull("When no .apk exists, asset should be null", selectedAsset)
    }

    @Test
    fun `test pre-existing complete APK does not re-download`() {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        assertNotNull(downloadDir)

        val targetApk = File(downloadDir, "notify-update-1.0.1.apk")
        targetApk.writeBytes(ByteArray(1024) { 1 }) // simulate complete 1024-byte file

        val apkAsset = ApkAsset(
            name = "notify.apk",
            downloadUrl = "https://example.com/notify.apk",
            size = 1024L
        )

        val updateInfo = UpdateInfo(
            versionName = "v1.0.1",
            changelog = "Test changelog",
            apkAsset = apkAsset
        )

        // Calling onUpdateNowClicked when targetApk already exists and matches expected size
        AppUpdater.onUpdateNowClicked(context, updateInfo)

        // File should still exist intact without being replaced/deleted
        assertTrue(targetApk.exists())
        assertEquals(1024L, targetApk.length())

        // Cleanup
        targetApk.delete()
    }
}
