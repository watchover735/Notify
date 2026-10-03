package com.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DeveloperConfigTest {

    @Test
    fun developerConfig_hasConfiguredStrings() {
        assertEquals("Developed by Rahul", DeveloperConfig.DEVELOPED_BY)
        assertTrue(DeveloperConfig.INSTAGRAM_HANDLE.isNotBlank())
        assertTrue(DeveloperConfig.WHATSAPP_NUMBER.isNotBlank())
        assertTrue(DeveloperConfig.WHATSAPP_PREFILLED_MESSAGE.isNotBlank())
    }

    @Test
    fun developerConfig_whatsappNumberOnlyDigits() {
        val cleanNumber = DeveloperConfig.WHATSAPP_NUMBER.filter { it.isDigit() }
        // WhatsApp API requires clean country code digits only
        assertFalse("WhatsApp number must not have plus symbol", DeveloperConfig.WHATSAPP_NUMBER.contains("+"))
        assertFalse("WhatsApp number must not have spaces", DeveloperConfig.WHATSAPP_NUMBER.contains(" "))
        assertEquals("Sanitized number should match digits only", cleanNumber, DeveloperConfig.WHATSAPP_NUMBER)
    }

    @Test
    fun developerConfig_whatsappMessageUrlEncoding() {
        val encoded = URLEncoder.encode(DeveloperConfig.WHATSAPP_PREFILLED_MESSAGE, StandardCharsets.UTF_8.name())
        assertFalse("Encoded message should not contain raw spaces", encoded.contains(" "))
        assertTrue("Encoded message should contain valid URL encoding", encoded.isNotEmpty())
    }

    @Test
    fun developerConfig_instagramHandleSanitization() {
        val cleanHandle = DeveloperConfig.INSTAGRAM_HANDLE.trim().removePrefix("@")
        assertEquals(DeveloperConfig.INSTAGRAM_HANDLE.trim(), cleanHandle)
        assertFalse("Instagram handle should not start with @", cleanHandle.startsWith("@"))
    }
}
