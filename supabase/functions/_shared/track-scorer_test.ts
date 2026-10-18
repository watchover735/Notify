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

// ============================================================================
// B.6 — STEP A REAL CANDIDATE FIXTURES & 12 REGRESSION TESTS
// ============================================================================

// (1) STEP A JioSaavn Candidates Fixture Test
Deno.test("stepA_jiosaavn_jeene_laga_hoon_rejected_returns_null", () => {
  const jiosaavnCandidates: CandidateTrack[] = [
    {
      id: "yU2bZuPl",
      title: "Jeene Laga Hoon (Instrumental)",
      artist: "Sachin-Jigar",
      label: "Tips Music Limited",
      durationMs: 546000
    },
    {
      id: "VGYtEAg3",
      title: "Jeene Laga Hoon (From\"Ramaiya Vastavaiya\") [Instrumental]",
      artist: "Sachin-Jigar",
      label: "Tips Music Limited",
      durationMs: 546000
    },
    {
      id: "AkL4O_5Z",
      title: "Jeene Laga Hoon",
      artist: "Jakiruddin Khan",
      label: "JMR MUSIC STUDIO",
      durationMs: 230000
    },
    {
      id: "SywIDLbE",
      title: "Jeene Laga Hoon",
      artist: "Aashu Soni",
      label: "Aashu soni",
      durationMs: 197000
    },
    {
      id: "-3xrUGkv",
      title: "Jeene Laga Hoon x Raatein Guzaari (Mashup)",
      artist: "Aditya Rikhari, Shreya Ghoshal, Aashu Soni",
      label: "Tips Music Limited",
      durationMs: 194000
    }
  ];

  const { winner, scoredList } = selectBestCandidate(jiosaavnCandidates, {
    query: "Jeene Laga Hoon Atif Aslam & Shreya Ghoshal",
    expectedTitle: "Jeene Laga Hoon",
    expectedArtist: "Atif Aslam & Shreya Ghoshal",
    expectedDurationMs: 236000
  });

  // Verify each candidate is rejected
  const j1 = scoredList.find((s) => s.candidate.id === "yU2bZuPl");
  assertEquals(j1?.isRejected, true);
  assertEquals(j1?.rejectReason, "unrequested_modifier: instrumental");

  const j2 = scoredList.find((s) => s.candidate.id === "VGYtEAg3");
  assertEquals(j2?.isRejected, true);
  assertEquals(j2?.rejectReason, "unrequested_modifier: instrumental");

  const j3 = scoredList.find((s) => s.candidate.id === "AkL4O_5Z");
  assertEquals(j3?.isRejected, true, "Jakiruddin Khan must be rejected for Atif Aslam expected artist");
  assertEquals(j3?.rejectReason, "artist_mismatch");

  const j4 = scoredList.find((s) => s.candidate.id === "SywIDLbE");
  assertEquals(j4?.isRejected, true, "Aashu Soni must be rejected for Atif Aslam expected artist");
  assertEquals(j4?.rejectReason, "artist_mismatch");

  const j5 = scoredList.find((s) => s.candidate.id === "-3xrUGkv");
  assertEquals(j5?.isRejected, true);
  assertEquals(j5?.rejectReason, "unrequested_modifier: mashup");

  // Winner must be null (no safe match, letting YouTube FastInnerTube win the race)
  assertEquals(winner, null, "JioSaavn winner must be null when all candidates fail");
});

// (2) STEP A SoundCloud Candidates Fixture Test
Deno.test("stepA_soundcloud_jeene_laga_hoon_rejected_returns_null", () => {
  const soundcloudCandidates: CandidateTrack[] = [
    {
      id: 337965409,
      title: "Jeena Laga Hoon ft.Atif Aslam (Priom FTW Remix) 2017 Future EDM.mp3",
      uploader: "Priom FTW",
      artist: "Priom FTW",
      durationMs: 267461
    },
    {
      id: 2403951459,
      title: "Atif Aslam - Tera Hone Laga Hoon (Kaushal Edit)",
      uploader: "Kaushal (IN)",
      artist: "Kaushal (IN)",
      durationMs: 330046
    },
    {
      id: 1201005727,
      title: "Tera Hoone laga hoon | Atif Aslam | Cover",
      uploader: "Avi",
      artist: "Avi",
      durationMs: 173140
    },
    {
      id: 1399239274,
      title: "Atif_Aslam_Mashup___Amtee___Tu_Jane_Na___Tera_Hone_Laga_Hoon_____Chill_Trap_Beats(256k).mp3",
      uploader: "User 264404021",
      artist: "User 264404021",
      durationMs: 401219
    },
    {
      id: 933679369,
      title: "Tera Hone Laga Hoon - Atif Aslam (Night Mix)",
      uploader: "Shermix",
      artist: "Shermix",
      durationMs: 200080
    },
    {
      id: 1353301675,
      title: "tera hone laga hoon lofi | atif aslam | slowed, reverb & lofi",
      uploader: "Beats by Shaz",
      artist: "Beats by Shaz",
      durationMs: 423928
    }
  ];

  const { winner } = selectBestCandidate(soundcloudCandidates, {
    query: "Jeene Laga Hoon Atif Aslam",
    expectedTitle: "Jeene Laga Hoon",
    expectedArtist: "Atif Aslam",
    expectedDurationMs: 236000
  });

  assertEquals(winner, null, "SoundCloud must return null for remix/cover/mashup candidates");
});

// (3) B.3 New Modifiers Detection Tests
Deno.test("modifiers_new_words_detection", () => {
  const newModifiers = [
    { title: "Jeene Laga Hoon (Reprise)", mod: "reprise" },
    { title: "Jeene Laga Hoon (Female Version)", mod: "female" },
    { title: "Jeene Laga Hoon (Male Version)", mod: "male" },
    { title: "Jeene Laga Hoon (Sad Version)", mod: "sad" },
    { title: "Jeene Laga Hoon (Remake)", mod: "remake" },
    { title: "Jeene Laga Hoon (Tribute)", mod: "tribute" },
    { title: "Jeene Laga Hoon (Revisited)", mod: "revisited" },
    { title: "Jeene Laga Hoon (Rework)", mod: "rework" },
    { title: "Jeene Laga Hoon (Redux)", mod: "redux" },
    { title: "Jeene Laga Hoon (Unwind Mix)", mod: "unwind" },
    { title: "Jeene Laga Hoon (Chillout)", mod: "chill" },
  ];

  for (const item of newModifiers) {
    const comp = evaluateModifierCompatibility(item.title, "Jeene Laga Hoon", "Atif Aslam");
    assertEquals(comp.compatible, false, `${item.title} must be rejected by modifier ${item.mod}`);
  }
});

// ============================================================================
// 12 REGRESSION TESTS (ALL MUST ACCEPT)
// ============================================================================

// Regression 1: "Arijit Singh" vs "Arijit Singh, Pritam"
Deno.test("regression_1_artist_arijit_vs_arijit_pritam", () => {
  const cand: CandidateTrack = {
    id: "r1",
    title: "Kesariya",
    artist: "Arijit Singh, Pritam",
    durationMs: 268000
  };
  const scored = scoreCandidate(cand, {
    query: "Kesariya",
    expectedTitle: "Kesariya",
    expectedArtist: "Arijit Singh",
    expectedDurationMs: 268000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 2: "Atif Aslam & Shreya Ghoshal" vs "Atif Aslam"
Deno.test("regression_2_artist_atif_shreya_vs_atif", () => {
  const cand: CandidateTrack = {
    id: "r2",
    title: "Jeene Laga Hoon",
    artist: "Atif Aslam",
    durationMs: 236000
  };
  const scored = scoreCandidate(cand, {
    query: "Jeene Laga Hoon",
    expectedTitle: "Jeene Laga Hoon",
    expectedArtist: "Atif Aslam & Shreya Ghoshal",
    expectedDurationMs: 236000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 3: expectedArtist = "T-Series" + trusted label candidate
Deno.test("regression_3_expected_label_tseries_accepted", () => {
  const cand: CandidateTrack = {
    id: "r3",
    title: "Tum Hi Ho",
    artist: "Arijit Singh",
    label: "T-Series",
    durationMs: 262000
  };
  const scored = scoreCandidate(cand, {
    query: "Tum Hi Ho",
    expectedTitle: "Tum Hi Ho",
    expectedArtist: "T-Series",
    expectedDurationMs: 262000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 4: expectedArtist = "Tips Official" + trusted label candidate
Deno.test("regression_4_expected_label_tips_official_accepted", () => {
  const cand: CandidateTrack = {
    id: "r4",
    title: "Jeene Laga Hoon",
    artist: "Atif Aslam",
    label: "Tips Music Limited",
    durationMs: 236000
  };
  const scored = scoreCandidate(cand, {
    query: "Jeene Laga Hoon",
    expectedTitle: "Jeene Laga Hoon",
    expectedArtist: "Tips Official",
    expectedDurationMs: 236000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 5: "X - Topic" channel expected artist
Deno.test("regression_5_topic_channel_expected_artist_accepted", () => {
  const cand: CandidateTrack = {
    id: "r5",
    title: "Kesariya",
    artist: "Pritam, Arijit Singh",
    durationMs: 268000
  };
  const scored = scoreCandidate(cand, {
    query: "Kesariya",
    expectedTitle: "Kesariya",
    expectedArtist: "Pritam - Topic",
    expectedDurationMs: 268000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 6: Devanagari artist
Deno.test("regression_6_devanagari_artist_accepted", () => {
  const cand: CandidateTrack = {
    id: "r6",
    title: "Kesariya",
    artist: "अरिजीत सिंह",
    durationMs: 268000
  };
  const scored = scoreCandidate(cand, {
    query: "Kesariya",
    expectedTitle: "Kesariya",
    expectedArtist: "अरिजीत सिंह",
    expectedDurationMs: 268000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 7: Candidate artist missing + exact title + duration delta <= 3s
Deno.test("regression_7_missing_artist_exact_title_duration_accepted", () => {
  const cand: CandidateTrack = {
    id: "r7",
    title: "Kesariya",
    artist: null,
    durationMs: 268000
  };
  const scored = scoreCandidate(cand, {
    query: "Kesariya",
    expectedTitle: "Kesariya",
    expectedArtist: "Arijit Singh",
    expectedDurationMs: 268000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 8: "feat." in artist string
Deno.test("regression_8_feat_in_artist_string_accepted", () => {
  const cand: CandidateTrack = {
    id: "r8",
    title: "Baby",
    artist: "Justin Bieber feat. Ludacris",
    durationMs: 214000
  };
  const scored = scoreCandidate(cand, {
    query: "Baby",
    expectedTitle: "Baby",
    expectedArtist: "Justin Bieber",
    expectedDurationMs: 214000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 9: DJ Khaled and DJ Snake not rejected as modifiers
Deno.test("regression_9_dj_artist_names_accepted", () => {
  const candKhaled: CandidateTrack = {
    id: "r9a",
    title: "Wild Thoughts",
    artist: "DJ Khaled, Rihanna, Bryson Tiller",
    durationMs: 204000
  };
  const scoredKhaled = scoreCandidate(candKhaled, {
    query: "Wild Thoughts",
    expectedTitle: "Wild Thoughts",
    expectedArtist: "DJ Khaled",
    expectedDurationMs: 204000
  });
  assertEquals(scoredKhaled.isRejected, false);

  const candSnake: CandidateTrack = {
    id: "r9b",
    title: "Taki Taki",
    artist: "DJ Snake, Selena Gomez, Ozuna, Cardi B",
    durationMs: 212000
  };
  const scoredSnake = scoreCandidate(candSnake, {
    query: "Taki Taki",
    expectedTitle: "Taki Taki",
    expectedArtist: "DJ Snake",
    expectedDurationMs: 212000
  });
  assertEquals(scoredSnake.isRejected, false);
});

// Regression 10: Clean song "Kesariya" (Arijit Singh)
Deno.test("regression_10_clean_song_kesariya_accepted", () => {
  const cand: CandidateTrack = {
    id: "r10",
    title: "Kesariya",
    artist: "Pritam, Arijit Singh, Amitabh Bhattacharya",
    label: "Sony Music India",
    durationMs: 268000
  };
  const scored = scoreCandidate(cand, {
    query: "Kesariya",
    expectedTitle: "Kesariya",
    expectedArtist: "Arijit Singh",
    expectedDurationMs: 268000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 11: Clean song "Tum Hi Ho" (Arijit Singh)
Deno.test("regression_11_clean_song_tum_hi_ho_accepted", () => {
  const cand: CandidateTrack = {
    id: "r11",
    title: "Tum Hi Ho",
    artist: "Mithoon, Arijit Singh",
    label: "T-Series",
    durationMs: 262000
  };
  const scored = scoreCandidate(cand, {
    query: "Tum Hi Ho",
    expectedTitle: "Tum Hi Ho",
    expectedArtist: "Arijit Singh",
    expectedDurationMs: 262000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});

// Regression 12: Clean song "Agar Tum Saath Ho" (Alka Yagnik & Arijit Singh)
Deno.test("regression_12_clean_song_agar_tum_saath_ho_accepted", () => {
  const cand: CandidateTrack = {
    id: "r12",
    title: "Agar Tum Saath Ho",
    artist: "Alka Yagnik, Arijit Singh",
    label: "T-Series",
    durationMs: 341000
  };
  const scored = scoreCandidate(cand, {
    query: "Agar Tum Saath Ho",
    expectedTitle: "Agar Tum Saath Ho",
    expectedArtist: "Alka Yagnik & Arijit Singh",
    expectedDurationMs: 341000
  });
  assertEquals(scored.isRejected, false);
  assertEquals(scored.score >= MIN_SCORE, true);
});
