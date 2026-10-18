/**
 * Shared Track Scorer & Version Modifier Filter for Supabase Edge Functions.
 *
 * Used by resolve-jiosaavn and resolve-soundcloud to score and select the best
 * candidate track among search results while eliminating wrong versions
 * (remix, slowed, reverb, lofi, live, dj, mashup, etc.).
 *
 * Pure function module with zero external network dependencies.
 */

export const MIN_SCORE = 45; // Minimum score threshold (out of 100) to accept candidate

export const ARTIST_MIN_TOKEN_LEN = 3;
export const EXACT_DURATION_DELTA_MS = 3000; // 3 seconds tolerance for exact duration match

export const TRUSTED_LABELS: string[] = [
  "t-series", "tseries",
  "tips official", "tips music", "tips",
  "sony music", "sonymusic",
  "zee music", "zeemusic",
  "saregama",
  "yrf", "yash raj films",
  "universal music",
  "times music",
  "eros now", "eros music",
  "speed records",
  "geet mp3",
  "white hill"
];

export const LABEL_PATTERNS: RegExp[] = [
  /\bvevo\b/i,
  /\btopic\b/i,
  /\bofficial\b/i,
  /\brecords\b/i,
  /\bmusic\b/i,
  /\bentertainment\b/i
];

export const MODIFIER_WORDS: string[] = [
  "slow", "slowed",
  "reverb",
  "lofi", "lo-fi",
  "sped up", "speed up",
  "nightcore",
  "8d",
  "bass boosted",
  "mashup",
  "remix",
  "dj",
  "edit",
  "cover",
  "karaoke",
  "instrumental",
  "acoustic",
  "unplugged",
  "jhankar",
  "flip",
  "mix",
  "extended",
  // B.3 Additions:
  "reprise",
  "female",
  "male",
  "sad",
  "remake",
  "tribute",
  "revisited",
  "rework",
  "redux",
  "unwind",
  "chill",
  "chillout"
];

export const LIVE_PATTERNS: string[] = [
  "(live", "[live", "- live", "– live", "— live",
  "live at", "live from", "live version", "live performance", "live session"
];

export const LEGIT_PHRASE_PATTERNS: RegExp[] = [
  /\boriginal mix\b/gi,
  /\boriginal version\b/gi,
  /\balbum version\b/gi,
  /\bofficial\b/gi,
  /\bremastered\b/gi,
  /\bfeat\.?\b/gi,
  /[\(\[]from [^\)\]]+[\)\]]/gi,
  /from the .{0,60}?soundtrack/gi,
  /[\(\[]original soundtrack[\)\]]/gi,
  /[\(\[]ost[\)\]]/gi,
];

export interface CandidateTrack {
  id: string | number;
  title: string;
  artist?: string | null;
  durationMs?: number | null;
  label?: string | null;
  uploader?: string | null;
  raw?: any;
}

export interface ScoredCandidate {
  candidate: CandidateTrack;
  score: number;
  durationScore: number;
  titleScore: number;
  artistScore: number;
  labelBoost: number;
  isRejected: boolean;
  rejectReason?: string;
  detectedModifiers: string[];
}

export interface ScorerOptions {
  query: string;
  expectedTitle?: string | null;
  expectedArtist?: string | null;
  expectedDurationMs?: number | null;
}

/**
 * Decodes standard HTML entities commonly found in JioSaavn and other search responses.
 */
export function decodeHtmlEntities(str: string): string {
  if (!str) return "";
  return str
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#039;|&apos;|&#x27;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#(\d+);/g, (_, dec) => {
      const code = Number(dec);
      return !isNaN(code) && code > 0 && code < 65536 ? String.fromCharCode(code) : "";
    });
}

/**
 * Normalizes title text for modifier comparison by decoding HTML entities,
 * lowercasing, and stripping legitimate phrases ("original mix", "remastered", etc.).
 */
export function normalizeForModifierCheck(text: string): string {
  if (!text || !text.trim()) return "";
  let s = decodeHtmlEntities(text).toLowerCase();
  for (const pattern of LEGIT_PHRASE_PATTERNS) {
    s = s.replace(pattern, " ");
  }
  return s.trim();
}

/**
 * Checks whether a modifier word/phrase exists in the normalized text with word-boundary semantics.
 * Preceded and followed by non-alphanumeric characters or string boundaries.
 */
export function containsModifier(normalizedText: string, phrase: string): boolean {
  if (!normalizedText || !phrase) return false;
  const escaped = phrase.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  // Lookarounds ensure word boundaries including whitespace, punctuation, brackets
  const regex = new RegExp(`(?<![a-z0-9])${escaped}(?![a-z0-9])`, "i");
  return regex.test(normalizedText);
}

/**
 * Returns true if text matches any unambiguous live performance pattern.
 */
export function hasLivePattern(normalizedText: string): boolean {
  if (!normalizedText) return false;
  const lower = normalizedText.toLowerCase();
  return LIVE_PATTERNS.some((pattern) => lower.includes(pattern));
}

/**
 * Extracts all detected modifier words/phrases from a title.
 */
export function extractModifiers(title: string): Set<string> {
  const result = new Set<string>();
  if (!title) return result;
  const norm = normalizeForModifierCheck(title);
  for (const word of MODIFIER_WORDS) {
    if (containsModifier(norm, word)) {
      result.add(word);
    }
  }
  if (hasLivePattern(norm)) {
    result.add("live");
  }
  return result;
}

/**
 * Returns true if the string contains Devanagari script characters (U+0900 to U+097F).
 */
export function containsDevanagari(text: string): boolean {
  return /[\u0900-\u097F]/.test(text);
}

/**
 * Tokenizes text into lowercase words of length >= 2, stripping punctuation.
 */
function tokenize(text: string): string[] {
  if (!text) return [];
  const decoded = decodeHtmlEntities(text).toLowerCase();
  // Strip common noisy words
  const cleaned = decoded
    .replace(/[()[\]{},._\-:;!?'"\\/|`~+*]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  const stopWords = new Set(["the", "a", "an", "and", "or", "in", "on", "at", "to", "for", "of", "from", "by", "with", "feat", "ft"]);
  return cleaned
    .split(" ")
    .map((w) => w.trim())
    .filter((w) => w.length >= 2 && !stopWords.has(w));
}

/**
 * Evaluates modifier compatibility between candidate and expected titles.
 * Returns { compatible: boolean, reason?: string, detectedModifiers: string[] }.
 */
export function evaluateModifierCompatibility(
  candidateTitle: string,
  expectedTitle: string,
  expectedArtist?: string | null,
  candidateArtist?: string | null
): { compatible: boolean; reason?: string; detectedModifiers: string[] } {
  if (!candidateTitle) {
    // Fail-open on empty candidate title
    return { compatible: true, detectedModifiers: [] };
  }

  const normCandidate = normalizeForModifierCheck(candidateTitle);
  const normExpected = normalizeForModifierCheck(expectedTitle);
  const normExpectedArtist = expectedArtist ? normalizeForModifierCheck(expectedArtist) : "";

  // Modifiers explicitly requested in the song title
  const requestedModifiers = extractModifiers(normExpected);
  // Modifiers detected in the candidate title
  const candidateModifiers = extractModifiers(normCandidate);
  const detectedList = Array.from(candidateModifiers);

  // If expectedArtist has "dj" (e.g. "DJ Khaled", "DJ Snake"), "dj" in candidate title is an artist prefix, not a modifier
  const expectedArtistHasDj = normExpectedArtist ? containsModifier(normExpectedArtist, "dj") : false;

  const candidateRealModifiers = new Set<string>();
  for (const m of candidateModifiers) {
    if (m === "dj" && expectedArtistHasDj) {
      continue;
    }
    candidateRealModifiers.add(m);
  }

  // Rule 1: Expected title has modifiers -> Candidate must have at least one matching modifier
  if (requestedModifiers.size > 0) {
    let hasMatching = false;
    for (const m of requestedModifiers) {
      if (candidateModifiers.has(m)) {
        hasMatching = true;
        break;
      }
    }
    if (!hasMatching) {
      return {
        compatible: false,
        reason: `expected_modifier_missing: ${Array.from(requestedModifiers).join(",")}`,
        detectedModifiers: detectedList,
      };
    }
    // Reject if candidate has extra unrequested modifiers
    for (const m of candidateRealModifiers) {
      if (!requestedModifiers.has(m)) {
        return {
          compatible: false,
          reason: `unrequested_modifier: ${m}`,
          detectedModifiers: detectedList,
        };
      }
    }
  } else {
    // Rule 2: Expected title has NO modifiers -> Candidate must have NO unrequested modifiers
    if (candidateRealModifiers.size > 0) {
      return {
        compatible: false,
        reason: `unrequested_modifier: ${Array.from(candidateRealModifiers).join(",")}`,
        detectedModifiers: detectedList,
      };
    }
  }

  // Rule 3: Live pattern check
  const candidateLive = hasLivePattern(normCandidate);
  const expectedLive = hasLivePattern(normExpected) || (normExpectedArtist ? hasLivePattern(normExpectedArtist) : false);
  if (candidateLive && !expectedLive) {
    return {
      compatible: false,
      reason: "unrequested_live_recording",
      detectedModifiers: detectedList.includes("live") ? detectedList : [...detectedList, "live"],
    };
  }
  if (expectedLive && !candidateLive) {
    return {
      compatible: false,
      reason: "expected_live_missing",
      detectedModifiers: detectedList,
    };
  }

  return { compatible: true, detectedModifiers: detectedList };
}

export function isLabelOrChannel(name?: string | null): boolean {
  if (!name || !name.trim()) return false;
  const lower = name.trim().toLowerCase();
  if (TRUSTED_LABELS.some((tl) => lower.includes(tl))) return true;
  return LABEL_PATTERNS.some((pat) => pat.test(lower));
}

export function isTrustedLabel(labelOrUploader?: string | null): boolean {
  if (!labelOrUploader || !labelOrUploader.trim()) return false;
  const lower = labelOrUploader.trim().toLowerCase();
  return TRUSTED_LABELS.some((tl) => lower.includes(tl));
}

/**
 * Evaluates the artist gate for candidate tracks.
 * Returns { pass: boolean, reason?: string, isLabelExpected: boolean }.
 */
export function evaluateArtistGate(
  candidate: CandidateTrack,
  expectedArtist: string | null | undefined,
  expectedTitle: string,
  expectedDurationMs: number | null | undefined
): { pass: boolean; reason?: string; isLabelExpected: boolean } {
  if (!expectedArtist || !expectedArtist.trim()) {
    return { pass: true, isLabelExpected: false };
  }

  const normExpectedArtist = expectedArtist.trim();
  const isLabel = isLabelOrChannel(normExpectedArtist);

  if (isLabel) {
    // LABEL / CHANNEL case:
    // Accept ONLY IF (label or uploader is TRUSTED_LABELS) OR (title exact + duration delta <= 3s)
    const hasTrustedLabel = isTrustedLabel(candidate.label) || isTrustedLabel(candidate.uploader);
    const candidateTokens = tokenize(candidate.title);
    const expectedTokens = tokenize(expectedTitle);
    const isExactTitle = expectedTokens.length > 0 && candidateTokens.length > 0 &&
      expectedTokens.length === candidateTokens.length &&
      expectedTokens.every((t) => candidateTokens.includes(t));
    const durationDelta = (expectedDurationMs && candidate.durationMs)
      ? Math.abs(candidate.durationMs - expectedDurationMs)
      : null;
    const isExactDuration = durationDelta !== null && durationDelta <= EXACT_DURATION_DELTA_MS;

    if (hasTrustedLabel || (isExactTitle && isExactDuration)) {
      return { pass: true, isLabelExpected: true };
    }
    return { pass: false, reason: "unverified_label_candidate", isLabelExpected: true };
  }

  // REAL ARTIST case:
  const candidateArtistRaw = [
    candidate.artist,
    candidate.uploader,
    candidate.label
  ].filter(Boolean).join(" ").trim();

  // Exceptions (reject NAHI, par tight check):
  // candidate artist empty/missing ho, ya non-Latin script me ho (Devanagari etc.)
  const candidateArtistMissing = !candidateArtistRaw;
  const candidateNonLatin = containsDevanagari(candidateArtistRaw);
  const expectedNonLatin = containsDevanagari(normExpectedArtist);

  if (candidateArtistMissing || candidateNonLatin || expectedNonLatin) {
    const candidateTokens = tokenize(candidate.title);
    const expectedTokens = tokenize(expectedTitle);
    const isExactTitle = expectedTokens.length > 0 && candidateTokens.length > 0 &&
      expectedTokens.length === candidateTokens.length &&
      expectedTokens.every((t) => candidateTokens.includes(t));
    const durationDelta = (expectedDurationMs && candidate.durationMs)
      ? Math.abs(candidate.durationMs - expectedDurationMs)
      : null;
    const isExactDuration = durationDelta !== null && durationDelta <= EXACT_DURATION_DELTA_MS;

    if (isExactTitle && isExactDuration) {
      return { pass: true, isLabelExpected: false };
    }
    return { pass: false, reason: "low_confidence_unverified_artist", isLabelExpected: false };
  }

  // Split expectedArtist on &, ,, feat, ft, x, and
  const artistParts = normExpectedArtist
    .split(/\s*(?:&|,|\bfeat\.?|\bft\.?|\bx\b|\band\b)\s*/i)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  const expectedArtistTokens: string[] = [];
  for (const part of artistParts) {
    const toks = tokenize(part).filter((t) => t.length >= ARTIST_MIN_TOKEN_LEN);
    expectedArtistTokens.push(...toks);
  }

  if (expectedArtistTokens.length === 0) {
    return { pass: true, isLabelExpected: false };
  }

  const candTokens = new Set(tokenize(candidateArtistRaw).filter((t) => t.length >= ARTIST_MIN_TOKEN_LEN));
  const hasMatch = expectedArtistTokens.some((tok) => candTokens.has(tok));
  if (!hasMatch) {
    return { pass: false, reason: "artist_mismatch", isLabelExpected: false };
  }

  return { pass: true, isLabelExpected: false };
}

/**
 * Scores a candidate track against options.
 * Output score ranges from 0 to 100.
 */
export function scoreCandidate(
  candidate: CandidateTrack,
  options: ScorerOptions
): ScoredCandidate {
  const effectiveExpectedTitle = (options.expectedTitle || options.query || "").trim();
  const effectiveArtist = (options.expectedArtist || "").trim();
  const expectedDur = options.expectedDurationMs && options.expectedDurationMs > 0 ? options.expectedDurationMs : null;
  const candidateDur = candidate.durationMs && candidate.durationMs > 0 ? candidate.durationMs : null;

  // 1. Modifier evaluation (Hard filter / penalty)
  const modCheck = evaluateModifierCompatibility(
    candidate.title,
    effectiveExpectedTitle,
    effectiveArtist
  );

  if (!modCheck.compatible) {
    return {
      candidate,
      score: 0,
      durationScore: 0,
      titleScore: 0,
      artistScore: 0,
      labelBoost: 0,
      isRejected: true,
      rejectReason: modCheck.reason,
      detectedModifiers: modCheck.detectedModifiers,
    };
  }

  // 2. Artist gate evaluation (B.1)
  const artistGate = evaluateArtistGate(
    candidate,
    effectiveArtist,
    effectiveExpectedTitle,
    expectedDur
  );

  if (!artistGate.pass) {
    return {
      candidate,
      score: 0,
      durationScore: 0,
      titleScore: 0,
      artistScore: 0,
      labelBoost: 0,
      isRejected: true,
      rejectReason: artistGate.reason,
      detectedModifiers: modCheck.detectedModifiers,
    };
  }

  // 3. Duration score (Max 50 points)
  let durationScore = 35; // neutral default when duration is unknown
  if (expectedDur !== null && candidateDur !== null) {
    const deltaMs = Math.abs(candidateDur - expectedDur);
    const tolerance20 = Math.max(15000, expectedDur * 0.20);
    const isSnippet = expectedDur > 60000 && candidateDur <= 35000;

    if (isSnippet) {
      durationScore = 0;
    } else if (deltaMs <= 5000) {
      durationScore = 50; // exact match within 5s
    } else if (deltaMs <= 15000) {
      durationScore = 45; // very close within 15s
    } else if (deltaMs <= expectedDur * 0.10) {
      durationScore = 40; // within 10%
    } else if (deltaMs <= tolerance20) {
      durationScore = 30; // within 20%
    } else {
      // Over 20% mismatch
      durationScore = 5;
    }
  }

  // 4. Symmetric title token score (B.2 - Max 35 points)
  let titleScore = 20; // default baseline
  const expectedTokens = tokenize(effectiveExpectedTitle);
  const candidateTokens = tokenize(candidate.title);

  // Check for Devanagari vs Romanized discrepancy
  const expectedDevanagari = containsDevanagari(effectiveExpectedTitle);
  const candidateDevanagari = containsDevanagari(candidate.title);

  if (expectedDevanagari !== candidateDevanagari && (expectedDevanagari || candidateDevanagari)) {
    titleScore = 25;
  } else if (expectedTokens.length > 0 && candidateTokens.length > 0) {
    const candidateSet = new Set(candidateTokens);
    let matchCount = 0;
    for (const token of expectedTokens) {
      if (candidateSet.has(token)) {
        matchCount++;
      }
    }
    const maxTokens = Math.max(expectedTokens.length, candidateTokens.length);
    const overlapRatio = maxTokens > 0 ? matchCount / maxTokens : 0;
    titleScore = Math.round(overlapRatio * 35);
  }

  // 5. Artist score (Max 15 points)
  let artistScore = 5; // default baseline
  if (artistGate.isLabelExpected) {
    artistScore = 10; // neutral positive baseline when expected artist is a label/channel
  } else {
    const artistToCheck = effectiveArtist || (options.query ? options.query : "");
    const artistTokens = tokenize(artistToCheck);
    const candidateArtistRaw = [candidate.title, candidate.artist, candidate.uploader, candidate.label].filter(Boolean).join(" ");
    const candidateAllTokens = tokenize(candidateArtistRaw);

    if (artistTokens.length > 0) {
      const candidateSet = new Set(candidateAllTokens);
      let matched = 0;
      for (const tok of artistTokens) {
        if (candidateSet.has(tok)) {
          matched++;
        }
      }
      const ratio = matched / artistTokens.length;
      artistScore = Math.round(ratio * 15);
    }
  }

  // 6. Trusted Label Boost (B.4 - +10 points)
  let labelBoost = 0;
  if (isTrustedLabel(candidate.label) || isTrustedLabel(candidate.uploader)) {
    labelBoost = 10;
  }

  const rawTotal = durationScore + titleScore + artistScore + labelBoost;
  const totalScore = Math.min(100, rawTotal);

  return {
    candidate,
    score: totalScore,
    durationScore,
    titleScore,
    artistScore,
    labelBoost,
    isRejected: totalScore < MIN_SCORE,
    rejectReason: totalScore < MIN_SCORE ? `low_score_${totalScore}_below_${MIN_SCORE}` : undefined,
    detectedModifiers: modCheck.detectedModifiers,
  };
}

/**
 * Evaluates an array of candidates and returns the best matching candidate,
 * applying tie-breaking logic based on duration closeness.
 */
export function selectBestCandidate(
  candidates: CandidateTrack[],
  options: ScorerOptions
): { winner: CandidateTrack | null; scoredList: ScoredCandidate[] } {
  if (!candidates || candidates.length === 0) {
    return { winner: null, scoredList: [] };
  }

  const scoredList = candidates.map((c) => scoreCandidate(c, options));

  // Filter candidates that meet MIN_SCORE and are not rejected
  const eligible = scoredList.filter((s) => !s.isRejected && s.score >= MIN_SCORE);

  if (eligible.length === 0) {
    return { winner: null, scoredList };
  }

  // Sort candidates:
  // 1. Higher score first
  // 2. If scores are close (within 5 points), tie-break by duration delta
  const expectedDur = options.expectedDurationMs && options.expectedDurationMs > 0 ? options.expectedDurationMs : null;

  eligible.sort((a, b) => {
    const scoreDiff = b.score - a.score;
    if (Math.abs(scoreDiff) > 5 || expectedDur === null) {
      return scoreDiff;
    }
    // Tie-break with duration closeness
    const aDur = a.candidate.durationMs || 0;
    const bDur = b.candidate.durationMs || 0;
    const aDelta = aDur > 0 ? Math.abs(aDur - expectedDur) : Number.MAX_SAFE_INTEGER;
    const bDelta = bDur > 0 ? Math.abs(bDur - expectedDur) : Number.MAX_SAFE_INTEGER;
    return aDelta - bDelta;
  });

  return { winner: eligible[0].candidate, scoredList };
}
