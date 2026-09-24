package com.notify.core.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioPermissionHelperTest {

    @Test
    fun testRequiredPermissionAcrossApiLevels() {
        // API 26 (Android 8.0 Oreo) -> READ_EXTERNAL_STORAGE
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", LocalAudioPermissionHelper.requiredPermission(26))

        // API 30 (Android 11) -> READ_EXTERNAL_STORAGE
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", LocalAudioPermissionHelper.requiredPermission(30))

        // API 32 (Android 12L) -> READ_EXTERNAL_STORAGE
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", LocalAudioPermissionHelper.requiredPermission(32))

        // API 33 (Android 13 Tiramisu) -> READ_MEDIA_AUDIO
        assertEquals("android.permission.READ_MEDIA_AUDIO", LocalAudioPermissionHelper.requiredPermission(33))

        // API 34 (Android 14 UpsideDownCake) -> READ_MEDIA_AUDIO
        assertEquals("android.permission.READ_MEDIA_AUDIO", LocalAudioPermissionHelper.requiredPermission(34))

        // API 35 (Android 15 VanillaIceCream) -> READ_MEDIA_AUDIO
        assertEquals("android.permission.READ_MEDIA_AUDIO", LocalAudioPermissionHelper.requiredPermission(35))
    }
}
