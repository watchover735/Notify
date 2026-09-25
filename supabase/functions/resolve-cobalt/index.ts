const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

const DEFAULT_COBALT_INSTANCE = "https://api.cobalt.tools/api/json";
const FAILURE_THRESHOLD = 3;
const DEMOTION_COOLDOWN_MS = 10 * 60 * 1000; // 10 minutes

// Circuit breaker state in module scope
let consecutiveFailures = 0;
let demotedUntilEpochMs = 0;

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

function recordFailure() {
  consecutiveFailures++;
  if (consecutiveFailures >= FAILURE_THRESHOLD) {
    demotedUntilEpochMs = Date.now() + DEMOTION_COOLDOWN_MS;
  }
}

function recordSuccess() {
  consecutiveFailures = 0;
  demotedUntilEpochMs = 0;
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

  // Circuit breaker check
  const now = Date.now();
  if (now < demotedUntilEpochMs) {
    const remainingSec = Math.round((demotedUntilEpochMs - now) / 1000);
    return new Response(
      JSON.stringify({ success: false, error: `Cobalt demoted (cooldown ${remainingSec}s)` }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }

  try {
    let targetUrl = "";
    let videoId: string | null = null;

    if (req.method === "GET") {
      const u = new URL(req.url);
      targetUrl = u.searchParams.get("url") || "";
      videoId = u.searchParams.get("videoId");
    } else {
      const body = await req.json().catch(() => ({}));
      targetUrl = body.url || "";
      videoId = body.videoId || null;
    }

    if (!targetUrl && videoId) {
      targetUrl = `https://www.youtube.com/watch?v=${videoId}`;
    }

    const trimmedUrl = targetUrl.trim();
    if (!trimmedUrl) {
      return new Response(JSON.stringify({ success: false, error: "Missing url or videoId" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const cacheKey = (videoId || trimmedUrl).toLowerCase();
    const cached = cache.get(cacheKey);
    if (cached && Date.now() < cached.expiresAt) {
      return new Response(JSON.stringify({ ...cached.data, cached: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const instanceUrl = Deno.env.get("COBALT_INSTANCE_URL") || DEFAULT_COBALT_INSTANCE;

    const payload = {
      url: trimmedUrl,
      downloadMode: "audio",
      audioFormat: "best",
    };

    const resp = await fetch(instanceUrl, {
      method: "POST",
      headers: {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
      },
      body: JSON.stringify(payload),
    });

    if (!resp.ok) {
      recordFailure();
      return new Response(
        JSON.stringify({ success: false, error: `Cobalt HTTP ${resp.status}` }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const json = await resp.json().catch(() => null);
    const status = json?.status?.toLowerCase();
    const streamUrl = json?.url;

    const isValidStatus = ["stream", "redirect", "tunnel", "success"].includes(status);
    if (!isValidStatus || !streamUrl || typeof streamUrl !== "string" || !streamUrl.startsWith("http")) {
      recordFailure();
      return new Response(
        JSON.stringify({ success: false, error: `Invalid Cobalt response: status=${status}` }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    recordSuccess();

    const resultData = {
      success: true,
      provider: "cobalt",
      streamUrl: streamUrl,
      formatId: "cobalt_audio",
      mimeType: "audio/mp4",
      container: "m4a",
      expiresAtEpochMs: Date.now() + 3600000,
      fallbackUrls: [],
      videoId: videoId,
      cached: false,
    };

    cache.set(cacheKey, { data: resultData, expiresAt: Date.now() + 3600000 });

    return new Response(JSON.stringify(resultData), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    recordFailure();
    return new Response(JSON.stringify({ success: false, error: err.message || "Internal error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
