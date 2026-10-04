package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgotPasswordFlowTest {

    @Test
    fun maskEmail_masksProperly() {
        assertEquals("r***l@gmail.com", maskEmail("rahul@gmail.com"))
        assertEquals("a*b@xyz.com", maskEmail("amb@xyz.com"))
        assertEquals("*@domain.com", maskEmail("a@domain.com"))
    }

    @Test
    fun resetPasswordValidation_checksTokenAndNewPassword() {
        fun validateReset(token: String, newPass: String, confirmPass: String): String? {
            if (token.trim().length < 6) return "Verification code enter karein"
            if (newPass.length < 6) return "Password kam se kam 6 characters ka hona chahiye"
            if (newPass != confirmPass) return "Naya password aur confirm password match nahi karte"
            return null
        }

        assertEquals("Verification code enter karein", validateReset("123", "password123", "password123"))
        assertEquals("Password kam se kam 6 characters ka hona chahiye", validateReset("123456", "12345", "12345"))
        assertEquals("Naya password aur confirm password match nahi karte", validateReset("123456", "pass123", "pass456"))
        assertEquals(null, validateReset("123456", "pass123", "pass123"))
    }
}
