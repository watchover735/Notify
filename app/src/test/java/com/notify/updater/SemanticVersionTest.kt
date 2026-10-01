package com.notify.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `test multi-digit version comparison avoids string comparison trap`() {
        // Plain string comparison would evaluate "1.0.10" < "1.0.9" because '1' < '9'.
        // SemanticVersion must correctly evaluate 1.0.10 > 1.0.9.
        assertTrue("v1.0.10 must be newer than v1.0.9", SemanticVersion.isNewer("v1.0.10", "v1.0.9"))
        assertTrue("1.0.10 must be newer than 1.0.9", SemanticVersion.isNewer("1.0.10", "1.0.9"))
        assertFalse("1.0.9 must NOT be newer than 1.0.10", SemanticVersion.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun `test prefix handling`() {
        assertTrue(SemanticVersion.isNewer("v1.1.0", "1.0.0"))
        assertTrue(SemanticVersion.isNewer("V1.1.0", "1.0.0"))
        assertTrue(SemanticVersion.isNewer("1.1.0", "v1.0.0"))
        assertTrue(SemanticVersion.isNewer("v0.6.1", "0.6.0"))
    }

    @Test
    fun `test equal versions return false for isNewer`() {
        assertFalse(SemanticVersion.isNewer("1.0.0", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("v1.0.0", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("v1.0", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("0.6.0", "0.6.0"))
        assertFalse(SemanticVersion.isNewer("v0.6.0", "0.6.0"))
    }

    @Test
    fun `test differing segment counts`() {
        assertTrue(SemanticVersion.isNewer("1.0.1", "1.0"))
        assertFalse(SemanticVersion.isNewer("1.0", "1.0.1"))
        assertFalse(SemanticVersion.isNewer("1.0.0", "1.0"))
    }

    @Test
    fun `test older versions return false`() {
        assertFalse(SemanticVersion.isNewer("0.5.9", "0.6.0"))
        assertFalse(SemanticVersion.isNewer("v0.5.99", "0.6.0"))
        assertFalse(SemanticVersion.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun `test pre-release handling`() {
        // Final release is newer than release candidate
        assertTrue(SemanticVersion.isNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(SemanticVersion.isNewer("1.0.0-rc1", "1.0.0"))
        assertTrue(SemanticVersion.isNewer("1.0.0-rc2", "1.0.0-rc1"))
    }

    @Test
    fun `test malformed and empty inputs return null or false without crashing`() {
        assertNull(SemanticVersion.parse(null))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("   "))
        assertNull(SemanticVersion.parse("v"))
        assertNull(SemanticVersion.parse("not-a-version"))

        assertFalse(SemanticVersion.isNewer(null, "1.0.0"))
        assertFalse(SemanticVersion.isNewer("1.0.0", null))
        assertFalse(SemanticVersion.isNewer("invalid", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("1.0.0", "invalid"))
    }

    @Test
    fun `test parsing segments`() {
        val parsed = SemanticVersion.parse("v2.14.8")
        assertNotNull(parsed)
        assertEquals(listOf(2, 14, 8), parsed!!.segments)
        assertEquals("", parsed.preRelease)
    }
}
