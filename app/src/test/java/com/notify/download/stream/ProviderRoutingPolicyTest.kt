package com.notify.download.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRoutingPolicyTest {

    @Test
    fun shouldIncludeJioSaavn_returnsTrueForIndicScripts() {
        // Devanagari (Hindi)
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("केसरिया तेरा इश्क"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Tum Hi Ho", title = "तुम ही हो", artist = "अरिजीत सिंह"))

        // Gurmukhi (Punjabi)
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("ਸਿੱਧੂ ਮੂਸੇ ਵਾਲਾ"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Born to Shine", title = "ਬੌਰਨ ਟੂ ਸ਼ਾਈਨ", artist = "ਦਿਲਜੀਤ ਦੋਸਾਂਝ"))
    }

    @Test
    fun shouldIncludeJioSaavn_returnsTrueForKnownIndianArtists() {
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Kesariya Arijit Singh"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("G.O.A.T Diljit Dosanjh"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("295 Sidhu Moosewala"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Shayad", title = "Shayad", artist = "Pritam"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Brown Munde AP Dhillon"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Softly", title = "Softly", artist = "Karan Aujla"))
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("Jai Ho", title = "Jai Ho", artist = "A.R. Rahman"))
        // Haryanvi / regional artists test case from user bug report
        assertTrue(ProviderRoutingPolicy.shouldIncludeJioSaavn("2 Numbari Masoom Sharma"))
        val (matched, reason) = ProviderRoutingPolicy.evaluateJioSaavnRouting("2 Numbari Masoom Sharma")
        assertTrue(matched)
        org.junit.Assert.assertEquals("matched_artist", reason)
    }

    @Test
    fun shouldIncludeJioSaavn_returnsFalseForWesternTracks() {
        assertFalse(ProviderRoutingPolicy.shouldIncludeJioSaavn("Bohemian Rhapsody Queen"))
        assertFalse(ProviderRoutingPolicy.shouldIncludeJioSaavn("Counting Stars", title = "Counting Stars", artist = "OneRepublic"))
        assertFalse(ProviderRoutingPolicy.shouldIncludeJioSaavn("New Rules", title = "New Rules", artist = "Dua Lipa"))
        assertFalse(ProviderRoutingPolicy.shouldIncludeJioSaavn("Shape of You", title = "Shape of You", artist = "Ed Sheeran"))
        assertFalse(ProviderRoutingPolicy.shouldIncludeJioSaavn("Hotel California Eagles"))
    }
}
