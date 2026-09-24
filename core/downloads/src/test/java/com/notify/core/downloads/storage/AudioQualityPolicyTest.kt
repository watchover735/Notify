package com.notify.core.downloads.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualityPolicyTest {

    @Test
    fun audioQualityProfiles_containValidYtDlpOptions() {
        assertEquals("bestaudio[abr<=96]/bestaudio", AudioQualityProfile.DATA_SAVER.toYtDlpOption())
        assertEquals("bestaudio[abr<=160]/bestaudio", AudioQualityProfile.NORMAL.toYtDlpOption())
        assertEquals("bestaudio", AudioQualityProfile.HIGH.toYtDlpOption())
    }

    @Test
    fun audioQualityProfiles_haveReasonableBitrates() {
        assertTrue(AudioQualityProfile.DATA_SAVER.maxBitrateKbps <= 96)
        assertTrue(AudioQualityProfile.NORMAL.maxBitrateKbps <= 160)
        assertTrue(AudioQualityProfile.HIGH.maxBitrateKbps <= 320)
    }

    @Test
    fun audioQualityProfiles_supportStandardAudioExtensions() {
        assertTrue(AudioQualityProfile.NORMAL.preferredExtensions.contains("opus"))
        assertTrue(AudioQualityProfile.NORMAL.preferredExtensions.contains("m4a"))
        assertTrue(AudioQualityProfile.HIGH.preferredExtensions.contains("opus"))
    }
}
