package com.notify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangePasswordValidationTest {

    private fun validate(current: String, new: String, confirm: String): String? {
        if (current.isBlank()) return "Purana password enter karein"
        if (new.length < 6) return "Password kam se kam 6 characters ka hona chahiye"
        if (new != confirm) return "Naya password aur confirm password match nahi karte"
        if (new == current) return "Naya password purane password se alag hona chahiye"
        return null
    }

    @Test
    fun validation_rejectsBlankCurrentPassword() {
        val err = validate("", "newpass123", "newpass123")
        assertEquals("Purana password enter karein", err)
    }

    @Test
    fun validation_rejectsShortNewPassword() {
        val err = validate("oldpass123", "12345", "12345")
        assertEquals("Password kam se kam 6 characters ka hona chahiye", err)
    }

    @Test
    fun validation_rejectsMismatchedConfirmPassword() {
        val err = validate("oldpass123", "newpass123", "diffpass123")
        assertEquals("Naya password aur confirm password match nahi karte", err)
    }

    @Test
    fun validation_rejectsSameNewAndCurrentPassword() {
        val err = validate("oldpass123", "oldpass123", "oldpass123")
        assertEquals("Naya password purane password se alag hona chahiye", err)
    }

    @Test
    fun validation_acceptsValidPasswords() {
        val err = validate("oldpass123", "newpass456", "newpass456")
        assertNull(err)
    }
}
