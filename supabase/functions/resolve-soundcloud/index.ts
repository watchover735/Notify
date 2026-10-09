import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

const SEARCH_ENDPOINT = "https://api-v2.soundcloud.com/search/tracks";
const EMERGENCY_FALLBACK_CLIENT_ID = "Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo";
const CLIENT_ID_TTL_MS = 6 * 3600 * 1000; // 6 hours

const SCRIPT_REGEX = /src="(https:\/\/a-v2\.sndcdn\.com\/assets\/[^"]+\.js)"/g;
const CLIENT_ID_REGEX = /client_id[:=]"([a-zA-Z0-9]{32})"/;

// In-memory caching & rate limiter
let cachedClientId: { id: string; expiresAt: number } | null = null;
let lastKnownGoodClientId: string | null = null;

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

async function tryScrapeClientId(): Promise<string | null> {
  try {
    const resp = await fetch("https://soundcloud.com", {
      headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
    });
    if (!resp.ok) return null;
    const html = await resp.text();

    const scriptUrls: string[] = [];
    let match: RegExpExecArray | null;
    while ((match = SCRIPT_REGEX.exec(html)) !== null) {
      scriptUrls.push(match[1]);
    }

    // Check last few scripts where client_id is typically bundled
    const candidates = scriptUrls.slice(-4).reverse();
    for (const url of candidates) {
      try {
        const jsResp = await fetch(url);
        if (!jsResp.ok) continue;
        const js = await jsResp.text();
        const idMatch = CLIENT_ID_REGEX.exec(js);
        if (idMatch && idMatch[1]) {
          return idMatch[1];
        }
      } catch (_e) {
        // Continue to next script
      }
    }
    return null;
  } catch (_e) {
    return null;
  }
}

async function getOrScrapeClientId(forceRefresh = false): Promise<string> {
  const now = Date.now();
  if (!forceRefresh && cachedClientId && now < cachedClientId.expiresAt) {
    return cachedClientId.id;
  }

  const scraped = await tryScrapeClientId();
  if (scraped) {
    lastKnownGoodClientId = scraped;
    cachedClientId = { id: scraped, expiresAt: now + CLIENT_ID_TTL_MS };
    return scraped;
  }

  if (lastKnownGoodClientId) {
    cachedClientId = { id: lastKnownGoodClientId, expiresAt: now + CLIENT_ID_TTL_MS };
    return lastKnownGoodClientId;
  }

  return EMERGENCY_FALLBACK_CLIENT_ID;
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

    let expectedDurationMs: number | null = null;

    if (req.method === "GET") {
      const url = new URL(req.url);
      query = url.searchParams.get("query") || url.searchParams.get("q") || "";
      videoId = url.searchParams.get("videoId");
      const exp = url.searchParams.get("expectedDurationMs") || url.searchParams.get("durationMs");
      if (exp) expectedDurationMs = parseInt(exp, 10);
    } else {
      const body = await req.json().catch(() => ({}));
      query = body.query || body.q || [body.title, body.artist].filter(Boolean).join(" ");
      videoId = body.videoId || null;
      if (body.expectedDurationMs) expectedDurationMs = parseInt(String(body.expectedDurationMs), 10);
      else if (body.durationMs) expectedDurationMs = parseInt(String(body.durationMs), 10);
    }

    const trimmedQuery = query.trim();
    if (!trimmedQuery) {
      return new Response(JSON.stringify({ success: false, error: "Empty query" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Check cache
    const cacheKey = `${trimmedQuery.toLowerCase()}_${expectedDurationMs || 0}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() < cached.expiresAt) {
      return new Response(JSON.stringify({ ...cached.data, cached: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    let effectiveClientId = await getOrScrapeClientId();
    const encoded = encodeURIComponent(trimmedQuery);
    let searchUrl = `${SEARCH_ENDPOINT}?q=${encoded}&client_id=${effectiveClientId}&limit=5`;

    let searchResp = await fetch(searchUrl, {
      headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
    });

    // 401 retry
    if (searchResp.status === 401) {
      cachedClientId = null;
      effectiveClientId = await getOrScrapeClientId(true);
      searchUrl = `${SEARCH_ENDPOINT}?q=${encoded}&client_id=${effectiveClientId}&limit=5`;
      searchResp = await fetch(searchUrl, {
        headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
      });
    }

    if (!searchResp.ok) {
      return new Response(
        JSON.stringify({ success: false, error: `SoundCloud HTTP ${searchResp.status}` }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const searchJson = await searchResp.json().catch(() => null);
    const collection = searchJson?.collection;
    if (!Array.isArray(collection) || collection.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No tracks found on SoundCloud" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    let track = collection[0];
    if (expectedDurationMs && expectedDurationMs > 0 && collection.length > 1) {
      const tolerance = Math.max(15000, expectedDurationMs * 0.20);
      const matched = collection.find((t: any) => {
        const d = t?.duration || 0;
        return Math.abs(d - expectedDurationMs) <= tolerance;
      });
      if (matched) {
        track = matched;
      } else {
        const nonSnippet = collection.find((t: any) => (t?.duration || 0) > 45000);
        if (nonSnippet) {
          track = nonSnippet;
        }
      }
    }

    const transcodings = track?.media?.transcodings;
    if (!Array.isArray(transcodings) || transcodings.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No transcodings on SoundCloud track" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Prioritize progressive MP3; fallback to HLS
    let chosenTranscodingUrl: string | null = null;
    let isProgressive = false;

    for (const tc of transcodings) {
      const protocol = tc?.format?.protocol?.toLowerCase() || "";
      const url = tc?.url;
      if (url) {
        if (protocol === "progressive") {
          chosenTranscodingUrl = url;
          isProgressive = true;
          break;
        } else if (!chosenTranscodingUrl) {
          chosenTranscodingUrl = url;
          isProgressive = false;
        }
      }
    }

    if (!chosenTranscodingUrl) {
      return new Response(
        JSON.stringify({ success: false, error: "No valid transcoding URL found" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const separator = chosenTranscodingUrl.includes("?") ? "&" : "?";
    let streamResolveUrl = `${chosenTranscodingUrl}${separator}client_id=${effectiveClientId}`;

    let streamResp = await fetch(streamResolveUrl, {
      headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
    });

    if (streamResp.status === 401) {
      cachedClientId = null;
      effectiveClientId = await getOrScrapeClientId(true);
      streamResolveUrl = `${chosenTranscodingUrl}${separator}client_id=${effectiveClientId}`;
      streamResp = await fetch(streamResolveUrl, {
        headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
      });
    }

    if (!streamResp.ok) {
      return new Response(
        JSON.stringify({ success: false, error: `Transcoding resolution failed: ${streamResp.status}` }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const streamJson = await streamResp.json().catch(() => null);
    const directUrl = streamJson?.url;
    if (!directUrl || typeof directUrl !== "string" || !directUrl.startsWith("http")) {
      return new Response(
        JSON.stringify({ success: false, error: "Empty direct URL from SoundCloud" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const trackDuration = typeof track?.duration === "number" && track.duration > 0 ? track.duration : null;

    const resultData = {
      success: true,
      provider: "soundcloud",
      streamUrl: directUrl,
      formatId: isProgressive ? "soundcloud_mp3_progressive" : "soundcloud_hls",
      mimeType: isProgressive ? "audio/mpeg" : "application/x-mpegURL",
      container: isProgressive ? "mp3" : "m3u8",
      bitrate: 128000,
      durationMs: trackDuration,
      expiresAtEpochMs: Date.now() + 1800000, // 30 min expiration
      fallbackUrls: [],
      videoId: videoId,
      cached: false,
    };

    cache.set(cacheKey, { data: resultData, expiresAt: Date.now() + 1800000 });

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
