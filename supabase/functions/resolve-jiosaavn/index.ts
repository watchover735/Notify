import CryptoJS from "npm:crypto-js@4.2.0";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import {
  selectBestCandidate,
  CandidateTrack,
  decodeHtmlEntities,
  MIN_SCORE
} from "../_shared/track-scorer.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

const LEGACY_DES_KEY = "38346591";
const SEARCH_ENDPOINT = "https://www.jiosaavn.com/api.php";
const QUALITY_VARIANTS = [
  { suffix: "_320.mp4", bitrate: 320000, formatId: "jiosaavn_aac_320" },
  { suffix: "_160.mp4", bitrate: 160000, formatId: "jiosaavn_aac_160" },
  { suffix: "_96.mp4", bitrate: 96000, formatId: "jiosaavn_aac_96" },
];

// In-memory cache & rate limiter (persists across warm invocations)
const cache = new Map<string, { data: any; expiresAt: number }>();
const rateLimits = new Map<string, { count: number; resetAt: number }>();
const MAX_REQ_PER_MIN = 120;

function checkRateLimit(ip: string): boolean {
  const now = Date.now();
  const record = rateLimits.get(ip);
  if (!record || now > record.resetAt) {
    rateLimits.set(ip, { count: 1, resetAt: now + 60000 });
    return true;
  }
  if (record.count >= MAX_REQ_PER_MIN) {
    return false;
  }
  record.count++;
  return true;
}

function decryptMediaUrl(encryptedBase64: string): string | null {
  try {
    const cleaned = encryptedBase64.trim().replaceAll(".", "+");
    const key = CryptoJS.enc.Utf8.parse(LEGACY_DES_KEY);
    const decrypted = CryptoJS.DES.decrypt(
      { ciphertext: CryptoJS.enc.Base64.parse(cleaned) },
      key,
      {
        mode: CryptoJS.mode.ECB,
        padding: CryptoJS.pad.Pkcs7,
      }
    );
    const decryptedUrl = decrypted.toString(CryptoJS.enc.Utf8).trim();
    if (decryptedUrl.startsWith("http://") || decryptedUrl.startsWith("https://")) {
      return decryptedUrl;
    }
    return null;
  } catch (_e) {
    return null;
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return new Response(JSON.stringify({ success: false, error: "Unauthorized" }), {
      status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" }
    });
  }
  const supabaseClient = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_ANON_KEY") ?? "",
    { global: { headers: { Authorization: authHeader } }, auth: { persistSession: false } }
  );
  const { data: { user }, error: userError } = await supabaseClient.auth.getUser();
  if (userError || !user) {
    return new Response(JSON.stringify({ success: false, error: "Unauthorized: Invalid session" }), {
      status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" }
    });
  }
  const { data: entitlement, error: entitlementError } = await supabaseClient.rpc("get_entitlement");
  if (entitlementError || !entitlement || entitlement.status !== "active") {
    return new Response(JSON.stringify({ success: false, error: "Forbidden: Active license required" }), {
      status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" }
    });
  }

  const clientIp = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "anonymous";
  if (!checkRateLimit(clientIp)) {
    return new Response(JSON.stringify({ success: false, error: "Rate limit exceeded" }), {
      status: 429,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  try {
    let query = "";
    let videoId: string | null = null;
    let expectedTitle: string | null = null;
    let expectedArtist: string | null = null;
    let expectedDurationMs: number | null = null;

    if (req.method === "GET") {
      const url = new URL(req.url);
      query = url.searchParams.get("query") || url.searchParams.get("q") || "";
      videoId = url.searchParams.get("videoId");
      expectedTitle = url.searchParams.get("expectedTitle") || url.searchParams.get("title");
      expectedArtist = url.searchParams.get("expectedArtist") || url.searchParams.get("artist");
      const expDur = url.searchParams.get("expectedDurationMs") || url.searchParams.get("durationMs");
      if (expDur) {
        const parsed = parseInt(expDur, 10);
        if (!isNaN(parsed) && parsed > 0) expectedDurationMs = parsed;
      }
    } else {
      const body = await req.json().catch(() => ({}));
      expectedTitle = body.expectedTitle || body.title || null;
      expectedArtist = body.expectedArtist || body.artist || null;
      query = body.query || body.q || [expectedTitle, expectedArtist].filter(Boolean).join(" ");
      videoId = body.videoId || null;
      const rawExpDur = body.expectedDurationMs || body.durationMs;
      if (rawExpDur) {
        const parsed = parseInt(String(rawExpDur), 10);
        if (!isNaN(parsed) && parsed > 0) expectedDurationMs = parsed;
      }
    }

    const trimmedQuery = query.trim();
    if (!trimmedQuery) {
      return new Response(JSON.stringify({ success: false, error: "Empty query" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Check in-memory cache with v2: prefix to bypass pre-scorer wrong versions
    const cacheKey = `v2:${trimmedQuery.toLowerCase()}_${expectedDurationMs || 0}_${(expectedTitle || "").toLowerCase()}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() < cached.expiresAt) {
      return new Response(JSON.stringify({ ...cached.data, cached: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Query JioSaavn search API (request top 5 results for candidate evaluation)
    const encoded = encodeURIComponent(trimmedQuery);
    const searchUrl = `${SEARCH_ENDPOINT}?__call=search.getResults&_format=json&n=5&p=1&q=${encoded}&_marker=0&api_version=4&ctx=web6dot0`;

    const apiResp = await fetch(searchUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
      },
    });

    if (!apiResp.ok) {
      return new Response(
        JSON.stringify({ success: false, error: `JioSaavn HTTP ${apiResp.status}` }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const json = await apiResp.json().catch(() => null);
    const results = json?.results;
    if (!Array.isArray(results) || results.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No songs found on JioSaavn" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Map upstream search results to CandidateTrack format with HTML decoding & safe duration parsing
    const candidateTracks: CandidateTrack[] = results.map((item: any, idx: number) => {
      let durationMs: number | null = null;
      const rawDur = item?.more_info?.duration;
      if (rawDur !== undefined && rawDur !== null) {
        const durSec = parseInt(String(rawDur), 10);
        if (!isNaN(durSec) && durSec > 0) {
          durationMs = durSec * 1000;
        }
      }

      const decodedTitle = decodeHtmlEntities(item?.song || item?.title || "");
      const artistsList: string[] = [];
      if (item?.more_info?.artistMap?.primary_artists) {
        for (const a of item.more_info.artistMap.primary_artists) {
          if (a?.name) artistsList.push(a.name);
        }
      }
      if (item?.more_info?.artistMap?.artists) {
        for (const a of item.more_info.artistMap.artists) {
          if ((a?.role === "singer" || a?.role === "primary_artists") && a?.name) {
            artistsList.push(a.name);
          }
        }
      }
      if (item?.primary_artists) artistsList.push(item.primary_artists);
      if (item?.singers) artistsList.push(item.singers);
      if (item?.artist) artistsList.push(item.artist);
      const decodedArtist = decodeHtmlEntities(Array.from(new Set(artistsList)).join(", "));
      const label = item?.more_info?.label ? decodeHtmlEntities(String(item.more_info.label)) : null;

      return {
        id: item?.id || String(idx),
        title: decodedTitle,
        artist: decodedArtist,
        label,
        durationMs,
        raw: item,
      };
    });

    // Score all top 5 candidates with version modifier detection and tie-breaking
    const { winner, scoredList } = selectBestCandidate(candidateTracks, {
      query: trimmedQuery,
      expectedTitle: expectedTitle || null,
      expectedArtist: expectedArtist || null,
      expectedDurationMs: expectedDurationMs || null,
    });

    // Diagnostic logging for calibration and telemetry
    for (const s of scoredList) {
      console.log(
        `[JIOSAAVN_CANDIDATE] id=${s.candidate.id} title="${s.candidate.title}" durationMs=${s.candidate.durationMs}` +
        ` score=${s.score} rejected=${s.isRejected} reason=${s.rejectReason || "none"} mods=[${s.detectedModifiers.join(",")}]`
      );
    }

    if (!winner) {
      console.log(`[JIOSAAVN_DECISION] outcome=NO_SAFE_MATCH query="${trimmedQuery}" expectedDur=${expectedDurationMs}`);
      return new Response(
        JSON.stringify({ success: false, error: "no_safe_match" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Sort eligible candidates (score >= MIN_SCORE and not rejected) in descending score order
    const eligibleScored = scoredList
      .filter((s) => !s.isRejected && s.score >= MIN_SCORE)
      .sort((a, b) => b.score - a.score);

    let directStreamUrl: string | null = null;
    let fallbackStreamUrls: string[] = [];
    let selectedBitrate = 320000;
    let selectedFormatId = "jiosaavn_aac_320";
    let selectedDurationMs: number | null = null;
    let chosenCandidate: CandidateTrack | null = null;
    let chosenScore = 0;

    // Decrypt media URL for the best scoring eligible candidate; if decryption fails, try next best
    for (const scored of eligibleScored) {
      const candidate = scored.candidate;
      const moreInfo = candidate.raw?.more_info;
      if (!moreInfo) continue;

      const encUrl = moreInfo.encrypted_media_url;
      if (encUrl && typeof encUrl === "string") {
        const decrypted = decryptMediaUrl(encUrl);
        if (decrypted && decrypted.startsWith("http")) {
          const currentSuffix = QUALITY_VARIANTS.map((v) => v.suffix).find((s) =>
            decrypted.includes(s)
          );

          if (currentSuffix) {
            const best = QUALITY_VARIANTS[0]; // _320.mp4
            directStreamUrl = decrypted.replace(currentSuffix, best.suffix);
            selectedBitrate = best.bitrate;
            selectedFormatId = best.formatId;
            fallbackStreamUrls = QUALITY_VARIANTS.slice(1).map((v) =>
              decrypted.replace(currentSuffix, v.suffix)
            );
          } else {
            directStreamUrl = decrypted;
          }
          selectedDurationMs = candidate.durationMs || null;
          chosenCandidate = candidate;
          chosenScore = scored.score;
          break;
        }
      }

      // Preview URL fallback (only on this scored eligible candidate; never bypass scoring)
      const previewUrl = moreInfo.media_preview_url;
      if (!directStreamUrl && previewUrl && typeof previewUrl === "string" && previewUrl.startsWith("http")) {
        directStreamUrl = previewUrl
          .replace("preview.saavncdn.com", "aac.saavncdn.com")
          .replace("_96_p.mp4", "_320.mp4");
        fallbackStreamUrls = [
          directStreamUrl.replace("_320.mp4", "_160.mp4"),
          directStreamUrl.replace("_320.mp4", "_96.mp4"),
        ];
        selectedDurationMs = candidate.durationMs || null;
        chosenCandidate = candidate;
        chosenScore = scored.score;
        break;
      }
    }

    if (!directStreamUrl || !chosenCandidate) {
      console.log(`[JIOSAAVN_DECISION] outcome=DECRYPT_FAILED query="${trimmedQuery}"`);
      return new Response(
        JSON.stringify({ success: false, error: "no_safe_match" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    console.log(
      `[JIOSAAVN_DECISION] outcome=CHOSEN id=${chosenCandidate.id} title="${chosenCandidate.title}"` +
      ` score=${chosenScore} durationMs=${selectedDurationMs}`
    );

    const resultData = {
      success: true,
      provider: "jiosaavn",
      streamUrl: directStreamUrl,
      formatId: selectedFormatId,
      mimeType: "audio/mp4",
      container: "m4a",
      bitrate: selectedBitrate,
      durationMs: selectedDurationMs,
      expiresAtEpochMs: Date.now() + 86400000, // 24-hr CDN token validity
      fallbackUrls: fallbackStreamUrls,
      videoId: videoId,
      cached: false,
      title: chosenCandidate.title,
      matchScore: chosenScore,
      candidatesConsidered: scoredList.length,
    };

    // Cache successful resolution for 1 hour
    cache.set(cacheKey, { data: resultData, expiresAt: Date.now() + 3600000 });

    return new Response(JSON.stringify(resultData), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    return new Response(JSON.stringify({ success: false, error: err.message || "Internal error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});

