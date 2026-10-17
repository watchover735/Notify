import { assertEquals, assertNotEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  evaluateModifierCompatibility,
  extractModifiers,
  scoreCandidate,
  selectBestCandidate,
  MIN_SCORE,
  CandidateTrack
} from "./track-scorer.ts";

// (a) Original vs "(Slowed + Reverb)" -> REJECT
Deno.test("caseA_original_vs_slowed_reverb_rejected", () => {
  const result = evaluateModifierCompatibility(
    "Jeene Laga Hoon (Slowed + Reverb)",
    "Jeene Laga Hoon",
    "Atif Aslam"
  );
  assertEquals(result.compatible, false, "Slowed + Reverb candidate must be rejected for clean query");
});

// (b) vs "Mashup" -> REJECT
Deno.test("caseB_original_vs_mashup_rejected", () => {
  const result = evaluateModifierCompatibility(
    "Tum Hi Ho Mashup 2024",
    "Tum Hi Ho",
    "Arijit Singh"
  );
  assertEquals(result.compatible, false, "Mashup candidate must be rejected for clean query");
});

// (c) vs "DJ Remix" -> REJECT
Deno.test("caseC_original_vs_dj_remix_rejected", () => {
  const result = evaluateModifierCompatibility(
    "Kesariya (DJ Remix)",
    "Kesariya",
    "Pritam, Arijit Singh"
  );
  assertEquals(result.compatible, false, "DJ Remix must be rejected for clean query");
});

// (d) query me "Remix" ho aur candidate clean, to reject (reverse rule)
Deno.test("caseD_expected_remix_vs_clean_candidate_rejected", () => {
  const result = evaluateModifierCompatibility(
    "Kesariya (Original)",
    "Kesariya - Remix",
    "Arijit Singh"
  );
  assertEquals(result.compatible, false, "Clean candidate must be rejected when user requested a Remix");
});

// (e) query me "Remix" ho aur candidate Remix, to accept
Deno.test("caseE_expected_remix_vs_remix_candidate_accepted", () => {
  const result = evaluateModifierCompatibility(
    "Kesariya (Dance Remix)",
    "Kesariya - Remix",
    "Arijit Singh"
  );
  assertEquals(result.compatible, true, "Remix candidate must be accepted when user requested Remix");
});

// (f) "Jeene Laga Hoon" vs "(Jhankar)" -> REJECT
Deno.test("caseF_jhankar_rejected", () => {
  const result = evaluateModifierCompatibility(
    "Jeene Laga Hoon (Jhankar Beats)",
    "Jeene Laga Hoon",
    "Atif Aslam"
  );
  assertEquals(result.compatible, false, "Jhankar candidate must be rejected for clean query");
});

// (g) "DJ Khaled" artist par false reject nahi
Deno.test("caseG_artist_dj_khaled_not_false_rejected", () => {
  const result = evaluateModifierCompatibility(
    "DJ Khaled - All I Do Is Win",
    "All I Do Is Win",
    "DJ Khaled"
  );
  assertEquals(result.compatible, true, "DJ Khaled must not trigger false DJ remix rejection");
});

Deno.test("caseG_artist_dj_snake_not_false_rejected", () => {
  const result = evaluateModifierCompatibility(
    "DJ Snake - Taki Taki",
    "Taki Taki",
    "DJ Snake"
  );
  assertEquals(result.compatible, true, "DJ Snake must not trigger false DJ remix rejection");
});

// (h) "Slowly" / "Follow Me" / "Discover" / "Mixed Feelings" false reject nahi (word boundaries)
Deno.test("caseH_word_boundaries_false_reject_prevention", () => {
  const modsSlowly = extractModifiers("Slowly Walking");
  assertEquals(modsSlowly.has("slow"), false, "slowly must not trigger slow");
  assertEquals(modsSlowly.has("slowed"), false, "slowly must not trigger slowed");

  const modsFollow = extractModifiers("Follow Me Now");
  assertEquals(modsFollow.has("slow"), false, "follow must not trigger slow");

  const modsDiscover = extractModifiers("Discover New Horizons");
  assertEquals(modsDiscover.has("cover"), false, "discover must not trigger cover");

  const modsMixed = extractModifiers("Mixed Feelings");
  assertEquals(modsMixed.has("mix"), false, "mixed must not trigger mix");

  const comp = evaluateModifierCompatibility("Mixed Feelings", "Mixed Feelings", null);
  assertEquals(comp.compatible, true, "Mixed Feelings must be accepted");
});

// (i) Devanagari vs romanized
Deno.test("caseI_devanagari_vs_romanized_handling", () => {
  const candidate: CandidateTrack = {
    id: "1",
    title: "Jeene Laga Hoon",
    artist: "Atif Aslam",
    durationMs: 236000,
  };
  const scored = scoreCandidate(candidate, {
    query: "जीने लगा हूँ",
    expectedTitle: "जीने लगा हूँ",
    expectedArtist: "Atif Aslam",
    expectedDurationMs: 237000,
  });
  assertEquals(scored.isRejected, false, "Devanagari query must not be rejected against romanized candidate");
  assertEquals(scored.score >= MIN_SCORE, true, `Score (${scored.score}) must be >= MIN_SCORE (${MIN_SCORE})`);
});

// (j) empty / null fields par exception nahi
Deno.test("caseJ_empty_and_null_fields_safe", () => {
  const candidate: CandidateTrack = {
    id: "1",
    title: "",
    artist: null,
    durationMs: null,
  };
  const scored = scoreCandidate(candidate, {
    query: "",
    expectedTitle: null,
    expectedArtist: null,
    expectedDurationMs: null,
  });
  // Must complete without throwing
  assertNotEquals(scored, null);
});

// (k) sab candidates bure ho to no_safe_match (winner === null)
Deno.test("caseK_all_bad_candidates_returns_null_winner", () => {
  const candidates: CandidateTrack[] = [
    { id: "1", title: "Kesariya (Slowed + Reverb)", durationMs: 268000 },
    { id: "2", title: "Kesariya (DJ Remix)", durationMs: 240000 },
    { id: "3", title: "Random Unrelated Track", durationMs: 60000 },
  ];
  const { winner, scoredList } = selectBestCandidate(candidates, {
    query: "Kesariya Arijit Singh",
    expectedTitle: "Kesariya",
    expectedArtist: "Arijit Singh",
    expectedDurationMs: 268000,
  });
  assertEquals(winner, null, "When all candidates are wrong versions or low score, winner must be null");
  assertEquals(scoredList.length, 3);
});

// (l) tie-break duration se
Deno.test("caseL_tie_break_by_duration", () => {
  const candidates: CandidateTrack[] = [
    { id: "1", title: "Kesariya", artist: "Arijit Singh", durationMs: 290000 }, // delta = 22s
    { id: "2", title: "Kesariya", artist: "Arijit Singh", durationMs: 269000 }, // delta = 1s (better tie-break)
  ];
  const { winner } = selectBestCandidate(candidates, {
    query: "Kesariya Arijit Singh",
    expectedTitle: "Kesariya",
    expectedArtist: "Arijit Singh",
    expectedDurationMs: 268000,
  });
  assertNotEquals(winner, null);
  assertEquals(winner?.id, "2", "Candidate with closest duration must win tie-break");
});
