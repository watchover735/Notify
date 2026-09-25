import CryptoJS from "npm:crypto-js@4.2.0";

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

    // Check in-memory cache
    const cacheKey = trimmedQuery.toLowerCase();
    const cached = cache.get(cacheKey);
    if (cached && Date.now() < cached.expiresAt) {
      return new Response(JSON.stringify({ ...cached.data, cached: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Query JioSaavn search API
    const encoded = encodeURIComponent(trimmedQuery);
    const searchUrl = `${SEARCH_ENDPOINT}?__call=search.getResults&_format=json&n=3&p=1&q=${encoded}&_marker=0&api_version=4&ctx=web6dot0`;

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

    let directStreamUrl: string | null = null;
    let fallbackStreamUrls: string[] = [];
    let selectedBitrate = 320000;
    let selectedFormatId = "jiosaavn_aac_320";

    for (const item of results) {
      const moreInfo = item?.more_info;
      if (!moreInfo) continue;

      const encUrl = moreInfo.encrypted_media_url;
      if (encUrl && typeof encUrl === "string") {
        const decrypted = decryptMediaUrl(encUrl);
        if (decrypted && decrypted.startsWith("http")) {
          // Detect existing quality suffix
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
          break;
        }
      }

      // Preview URL fallback
      const previewUrl = moreInfo.media_preview_url;
      if (!directStreamUrl && previewUrl && typeof previewUrl === "string" && previewUrl.startsWith("http")) {
        directStreamUrl = previewUrl
          .replace("preview.saavncdn.com", "aac.saavncdn.com")
          .replace("_96_p.mp4", "_320.mp4");
        fallbackStreamUrls = [
          directStreamUrl.replace("_320.mp4", "_160.mp4"),
          directStreamUrl.replace("_320.mp4", "_96.mp4"),
        ];
        break;
      }
    }

    if (!directStreamUrl) {
      return new Response(
        JSON.stringify({ success: false, error: "Could not decrypt/resolve media URL" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const resultData = {
      success: true,
      provider: "jiosaavn",
      streamUrl: directStreamUrl,
      formatId: selectedFormatId,
      mimeType: "audio/mp4",
      container: "m4a",
      bitrate: selectedBitrate,
      expiresAtEpochMs: Date.now() + 86400000, // 24-hr CDN token validity
      fallbackUrls: fallbackStreamUrls,
      videoId: videoId,
      cached: false,
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
