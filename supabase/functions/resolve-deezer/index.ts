const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

const SEARCH_ENDPOINT = "https://api.deezer.com/search";

// In-memory cache & rate limiter
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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
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

    if (req.method === "GET") {
      const url = new URL(req.url);
      query = url.searchParams.get("query") || url.searchParams.get("q") || "";
      videoId = url.searchParams.get("videoId");
    } else {
      const body = await req.json().catch(() => ({}));
      query = body.query || body.q || [body.title, body.artist].filter(Boolean).join(" ");
      videoId = body.videoId || null;
    }

    const trimmedQuery = query.trim();
    if (!trimmedQuery) {
      return new Response(JSON.stringify({ success: false, error: "Empty query" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const cacheKey = trimmedQuery.toLowerCase();
    const cached = cache.get(cacheKey);
    if (cached && Date.now() < cached.expiresAt) {
      return new Response(JSON.stringify({ ...cached.data, cached: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const encoded = encodeURIComponent(trimmedQuery);
    const searchUrl = `${SEARCH_ENDPOINT}?q=${encoded}&limit=1`;

    const resp = await fetch(searchUrl, {
      headers: { "User-Agent": "Mozilla/5.0 (Android; Mobile)" },
    });

    if (!resp.ok) {
      return new Response(
        JSON.stringify({ success: false, error: `Deezer HTTP ${resp.status}` }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const json = await resp.json().catch(() => null);
    const data = json?.data;
    if (!Array.isArray(data) || data.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No tracks found on Deezer" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const track = data[0];
    const previewUrl = track?.preview;
    if (!previewUrl || typeof previewUrl !== "string" || !previewUrl.startsWith("http")) {
      return new Response(
        JSON.stringify({ success: false, error: "No valid preview URL on Deezer track" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const resultData = {
      success: true,
      provider: "deezer",
      streamUrl: previewUrl,
      formatId: "deezer_preview_mp3_128",
      mimeType: "audio/mpeg",
      container: "mp3",
      bitrate: 128000,
      expiresAtEpochMs: Date.now() + 3600000, // 1 hour validity
      fallbackUrls: [],
      videoId: videoId,
      cached: false,
    };

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
