package com.notify.download.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * B1.6 — Unit tests for the wrong-version modifier filter.
 *
 * Tests cover pure functions: [ResolvedStreamProviderChain.containsModifier],
 * [ResolvedStreamProviderChain.normalizeForModifierCheck], and
 * [ResolvedStreamProviderChain.hasLivePattern].
 *
 * Integration (race-level) tests for the full validateStream path are in
 * [ParallelRacingResolverChainTest].
 */
@RunWith(RobolectricTestRunner::class)
class VersionModifierFilterTest {

    private lateinit var chain: ResolvedStreamProviderChain

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        StreamUrlCache.clear()
        ResolvedStreamProviderChain.clearInFlight()
        chain = ResolvedStreamProviderChain(context = ctx)
    }

    // ─── Helper: shortcuts ────────────────────────────────────────────────────

    private fun norm(s: String) = chain.normalizeForModifierCheck(s.lowercase())
    private fun has(text: String, word: String) = chain.containsModifier(norm(text), word)
    private fun live(text: String) = chain.hasLivePattern(norm(text))

    // ─── (a) Original vs "(Slowed + Reverb)" → modifier "slowed"/"reverb" detected ──

    @Test
    fun caseA_slowed_detectedInCandidateTitle() {
        assertTrue("slowed must be detected",
            has("Jeene Laga Hoon (Slowed + Reverb)", "slowed"))
    }

    @Test
    fun caseA_reverb_detectedInCandidateTitle() {
        assertTrue("reverb must be detected",
            has("Jeene Laga Hoon (Slowed + Reverb)", "reverb"))
    }

    @Test
    fun caseA_slowed_notInOriginalExpected() {
        assertFalse("'slowed' must NOT be detected in clean title",
            has("Jeene Laga Hoon", "slowed"))
    }

    // ─── (b) "Mashup" detected ────────────────────────────────────────────────

    @Test
    fun caseB_mashup_detected() {
        assertTrue("mashup must be detected",
            has("Tum Hi Ho Mashup 2024", "mashup"))
    }

    @Test
    fun caseB_mashup_notInOriginal() {
        assertFalse(has("Tum Hi Ho", "mashup"))
    }

    // ─── (c) "DJ Remix" detected ──────────────────────────────────────────────

    @Test
    fun caseC_dj_detected() {
        assertTrue("dj must be detected in 'DJ Remix'",
            has("Kesariya (DJ Remix)", "dj"))
    }

    @Test
    fun caseC_remix_detected() {
        assertTrue("remix must be detected in 'DJ Remix'",
            has("Kesariya (DJ Remix)", "remix"))
    }

    @Test
    fun caseC_dj_notInOriginal() {
        assertFalse("'dj' must NOT be detected in 'Kesariya'",
            has("Kesariya", "dj"))
    }

    // ─── (d) Expected "Remix" and candidate clean → REVERSE RULE (verified externally) ──
    // The reverse rule is enforced by validateStream; here we verify the symmetric
    // helper function reports the state correctly:

    @Test
    fun caseD_remix_inExpectedButNotInStream() {
        // Stream: clean. Expected: remix. Reverse rule must fire externally.
        assertFalse("'remix' must NOT be detected in clean title",
            has("Jeene Laga Hoon", "remix"))
        assertTrue("'remix' MUST be detected in expected title",
            has("Jeene Laga Hoon (DJ Remix)", "remix"))
    }

    // ─── (e) Expected "Remix" + candidate "Remix" → BOTH have it → accepted ──

    @Test
    fun caseE_bothHaveRemix_bothDetected() {
        assertTrue(has("Jeene Laga Hoon (DJ Remix)", "remix"))
        assertTrue(has("Jeene Laga Hoon Remix 2024", "remix"))
        // Both true → no mismatch in validateStream (both || neither → OK)
    }

    // ─── (f) Jhankar detected ─────────────────────────────────────────────────

    @Test
    fun caseF_jhankar_detected() {
        assertTrue("jhankar must be detected",
            has("Jeene Laga Hoon (Jhankar)", "jhankar"))
    }

    @Test
    fun caseF_jhankar_notInOriginal() {
        assertFalse(has("Jeene Laga Hoon", "jhankar"))
    }

    // ─── (g) Artist "DJ Khaled" — "dj" in artist name, not a modifier ─────────
    // The validateStream function appends expectedArtist to the normExpectedWithArtist
    // string. So if artist = "DJ Khaled", the expected side contains "dj" → no mismatch.
    // We verify: containsModifier detects "dj" in the artist string.

    @Test
    fun caseG_djKhaled_artistSideContainsDj() {
        // When artist "DJ Khaled" is appended to expected side, dj is present there too.
        val normExpectedWithArtist = norm("All I Do Is Win") + " " + "dj khaled"
        assertTrue("dj must be found in the artist-extended expected string",
            chain.containsModifier(normExpectedWithArtist, "dj"))
        // This means when stream also has "dj", both sides have it → no mismatch.
    }

    @Test
    fun caseG_djSnake_artistSideContainsDj() {
        val normExpectedWithArtist = norm("Taki Taki") + " " + "dj snake"
        assertTrue(chain.containsModifier(normExpectedWithArtist, "dj"))
    }

    // ─── (h) False-reject prevention: word-boundary semantics ─────────────────

    @Test
    fun caseH_slowly_doesNotMatch_slow() {
        // "slow" as modifier must NOT match inside "slowly"
        assertFalse("'slow' must NOT match in 'Slowly'",
            chain.containsModifier("slowly", "slow"))
    }

    @Test
    fun caseH_slowly_doesNotMatch_slowed() {
        assertFalse("'slowed' must NOT match in 'slowly'",
            chain.containsModifier("slowly", "slowed"))
    }

    @Test
    fun caseH_followMe_doesNotMatchSlowed() {
        assertFalse("'slowed' must NOT match in 'follow me'",
            chain.containsModifier("follow me", "slowed"))
    }

    @Test
    fun caseH_discover_doesNotMatchCover() {
        // "cover" must not match inside "discover"
        assertFalse("'cover' must NOT match in 'discover'",
            chain.containsModifier("discover", "cover"))
    }

    @Test
    fun caseH_mixedFeelings_doesNotMatchMix() {
        // "mix" must not match inside "mixed"
        assertFalse("'mix' must NOT match in 'mixed feelings'",
            chain.containsModifier("mixed feelings", "mix"))
    }

    @Test
    fun caseH_adjacent_doesNotMatchDj() {
        // "dj" must not match inside "adjacent"
        assertFalse("'dj' must NOT match in 'adjacent'",
            chain.containsModifier("adjacent", "dj"))
    }

    @Test
    fun caseH_edited_doesNotMatchEdit() {
        // "edit" with word-boundary must NOT match "edited"
        assertFalse("'edit' must NOT match in 'edited'",
            chain.containsModifier("edited highlights", "edit"))
    }

    // ─── (i) "Original Mix" should be accepted → legit phrase stripped ─────────

    @Test
    fun caseI_originalMix_stripped_beforeModifierCheck() {
        // After normalization, "original mix" is stripped → "mix" modifier not present
        val normalized = norm("Kesariya (Original Mix)")
        assertFalse("'mix' must NOT be found after 'original mix' is stripped",
            chain.containsModifier(normalized, "mix"))
    }

    @Test
    fun caseI_originalVersion_stripped() {
        val normalized = norm("Tum Hi Ho (Original Version)")
        assertFalse("'mix' modifier must be absent after normalization",
            chain.containsModifier(normalized, "mix"))
    }

    @Test
    fun caseI_remastered_stripped() {
        val normalized = norm("Hotel California (Remastered)")
        // No modifier words remain after stripping "remastered"
        assertFalse(chain.containsModifier(normalized, "remix"))
        assertFalse(chain.containsModifier(normalized, "slowed"))
    }

    @Test
    fun caseI_fromMovie_stripped() {
        val normalized = norm("Ek Dil Ek Jaan (From Padmaavat)")
        // "from padmaavat" suffix stripped → no modifier triggered
        assertFalse(chain.containsModifier(normalized, "remix"))
    }

    // ─── (j) Null/blank stream title → LOW_CONFIDENCE path (validated externally) ──
    // We verify here that normalizeForModifierCheck handles blank inputs gracefully.

    @Test
    fun caseJ_blankTitle_normalizeSafe() {
        val result = norm("")
        assertTrue("Blank title normalize should return empty or blank", result.isBlank())
    }

    @Test
    fun caseJ_nullTitleContainsModifier_returnsFalse() {
        assertFalse("containsModifier on blank text must return false",
            chain.containsModifier("", "remix"))
    }

    @Test
    fun caseJ_blankPhrase_returnsFalse() {
        assertFalse("containsModifier with blank phrase must return false",
            chain.containsModifier("some song remix", ""))
    }

    // ─── (k) Exception safety ─────────────────────────────────────────────────

    @Test
    fun caseK_noException_onNullInputs() {
        // None of these must throw
        chain.containsModifier("", "")
        chain.containsModifier("  ", "remix")
        chain.normalizeForModifierCheck("")
        chain.normalizeForModifierCheck("  ")
        chain.hasLivePattern("")
        chain.hasLivePattern("  ")
    }

    // ─── (l) Devanagari vs romanized — no modifier false-reject ──────────────

    @Test
    fun caseL_devanagariTitle_noModifierWords_noFalseReject() {
        // "जीने लगा हूँ" contains no ASCII modifier words → no hit
        val text = chain.normalizeForModifierCheck("जीने लगा हूँ")
        for (word in ResolvedStreamProviderChain.MODIFIER_WORDS) {
            assertFalse("'$word' must NOT match in Devanagari text",
                chain.containsModifier(text, word))
        }
    }

    @Test
    fun caseL_devanagariTitle_noLivePattern() {
        assertFalse(chain.hasLivePattern("जीने लगा हूँ"))
    }

    // ─── Live pattern tests ───────────────────────────────────────────────────

    @Test
    fun live_liveAt_detected() {
        assertTrue("'live at' pattern must be detected",
            live("Blinding Lights (Live at the O2)"))
    }

    @Test
    fun live_liveFrom_detected() {
        assertTrue("'live from' pattern must be detected",
            live("Shape of You Live from Wembley"))
    }

    @Test
    fun live_liveVersion_detected() {
        assertTrue("'live version' pattern must be detected",
            live("Bohemian Rhapsody (Live Version)"))
    }

    @Test
    fun live_openParen_detected() {
        assertTrue("'(live' pattern must be detected",
            live("Hotel California (Live)"))
    }

    @Test
    fun live_liveAndLetDie_notDetected() {
        // "Live and Let Die" does NOT match any live pattern (no context anchor)
        assertFalse("'Live and Let Die' must NOT trigger live pattern",
            live("Live and Let Die"))
    }

    @Test
    fun live_liveYourLife_notDetected() {
        assertFalse("'Live Your Life' must NOT trigger live pattern",
            live("Live Your Life"))
    }

    @Test
    fun live_liveLaVidaLoca_notDetected() {
        assertFalse("'Livin' La Vida Loca' must NOT trigger live pattern",
            live("Livin' La Vida Loca"))
    }

    // ─── MODIFIER_WORDS config sanity checks ──────────────────────────────────

    @Test
    fun modifierWordsList_containsAllRequiredWords() {
        val words = ResolvedStreamProviderChain.MODIFIER_WORDS
        listOf(
            "jhankar", "dj", "remix", "slowed", "slow", "reverb",
            "mashup", "nightcore", "karaoke", "instrumental", "acoustic",
            "bass boosted", "cover", "lofi", "lo-fi", "mix", "extended",
            "sped up", "speed up", "unplugged", "flip", "edit", "8d"
        ).forEach { word ->
            assertTrue("'$word' must be in MODIFIER_WORDS", words.contains(word))
        }
    }

    @Test
    fun modifierWordsList_doesNotContainLive() {
        // "live" handled via LIVE_PATTERNS, NOT in MODIFIER_WORDS
        assertFalse("'live' must NOT be in MODIFIER_WORDS (handled via LIVE_PATTERNS)",
            ResolvedStreamProviderChain.MODIFIER_WORDS.contains("live"))
    }

    @Test
    fun livePatternsList_containsRequiredPatterns() {
        val patterns = ResolvedStreamProviderChain.LIVE_PATTERNS
        listOf("(live", "live at", "live from", "live version", "- live").forEach { p ->
            assertTrue("'$p' must be in LIVE_PATTERNS", patterns.contains(p))
        }
    }

    // ─── B1.6 Specification Test Cases ────────────────────────────────────────

    @Test
    fun specCase1_kesariya_vs_kesariyaRemix_rejected() {
        assertFalse(
            "Kesariya vs Kesariya - Remix must be REJECTED",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Kesariya - Remix", "Kesariya")
        )
    }

    @Test
    fun specCase2_kesariya_vs_kesariyaSlowedReverb_rejected() {
        assertFalse(
            "Kesariya vs Kesariya - Slowed + Reverb must be REJECTED",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Kesariya - Slowed + Reverb", "Kesariya")
        )
    }

    @Test
    fun specCase3_kesariyaRemix_vs_kesariyaRemix_accepted() {
        assertTrue(
            "Kesariya - Remix vs Kesariya - Remix must be ACCEPTED",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Kesariya - Remix", "Kesariya - Remix")
        )
    }

    @Test
    fun specCase4_kesariyaRemix_vs_kesariyaOriginal_rejected() {
        assertFalse(
            "Kesariya - Remix vs Kesariya (Original) must be REJECTED (reverse rule)",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Kesariya (Original)", "Kesariya - Remix")
        )
    }

    @Test
    fun specCase5_follow_vs_follow_accepted() {
        assertTrue(
            "Follow vs Follow must be ACCEPTED (slow not triggered in follow)",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Follow", "Follow")
        )
    }

    @Test
    fun specCase6_slowDown_vs_slowDown_accepted() {
        assertTrue(
            "Slow Down vs Slow Down must be ACCEPTED (both contain slow)",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Slow Down", "Slow Down")
        )
    }

    @Test
    fun specCase7_mixedFeelings_vs_mixedFeelings_accepted() {
        assertTrue(
            "Mixed Feelings vs Mixed Feelings must be ACCEPTED (mix not triggered in mixed)",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Mixed Feelings", "Mixed Feelings")
        )
    }

    @Test
    fun specCase8_hotelCalifornia_vs_hotelCaliforniaLive_rejected() {
        assertFalse(
            "Hotel California vs Hotel California (Live at Capital Centre 1977) must be REJECTED",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Hotel California (Live at Capital Centre 1977)", "Hotel California")
        )
    }

    @Test
    fun specCase9_hotelCaliforniaLive_vs_hotelCaliforniaLive_accepted() {
        assertTrue(
            "Hotel California (Live) vs Hotel California (Live at Capital Centre 1977) must be ACCEPTED",
            ResolvedStreamProviderChain.isCandidateCompatible("test", "Hotel California (Live at Capital Centre 1977)", "Hotel California (Live)")
        )
    }

    @Test
    fun specCase10_candidateNullTitle_accepted() {
        assertTrue(
            "Candidate with null title must be ACCEPTED (fail-open)",
            ResolvedStreamProviderChain.isCandidateCompatible("test", null, "Kesariya")
        )
    }

    @Test
    fun specCase11_wordBoundary_discovered_and_slowly() {
        val discoveredMods = ResolvedStreamProviderChain.extractModifiers("discovered")
        assertFalse("'discovered' must NOT trigger 'cover'", discoveredMods.contains("cover"))
        assertTrue("'discovered' must have no modifiers", discoveredMods.isEmpty())

        val slowlyMods = ResolvedStreamProviderChain.extractModifiers("slowly")
        assertFalse("'slowly' must NOT trigger 'slow'", slowlyMods.contains("slow"))
        assertFalse("'slowly' must NOT trigger 'slowed'", slowlyMods.contains("slowed"))
        assertTrue("'slowly' must have no modifiers", slowlyMods.isEmpty())
    }
}
