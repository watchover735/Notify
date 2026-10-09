import CryptoJS from "npm:crypto-js@4.2.0";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

const JIOSAAVN_API = "https://www.jiosaavn.com/api.php";
const LEGACY_DES_KEY = "38346591";

// In-memory cache — trending data refreshes every 2 hours on this edge
const cache = new Map<string, { data: any; expiresAt: number }>();
const CACHE_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours

function decryptMediaUrl(encryptedBase64: string): string | null {
  try {
    const cleaned = encryptedBase64.trim().replaceAll(".", "+");
    const key = CryptoJS.enc.Utf8.parse(LEGACY_DES_KEY);
    const decrypted = CryptoJS.DES.decrypt(
      { ciphertext: CryptoJS.enc.Base64.parse(cleaned) },
      key,
      { mode: CryptoJS.mode.ECB, padding: CryptoJS.pad.Pkcs7 }
    );
    const url = decrypted.toString(CryptoJS.enc.Utf8).trim();
    return url.startsWith("http") ? url : null;
  } catch {
    return null;
  }
}

function stripHtml(str: string): string {
  return str.replace(/<[^>]*>/g, "").trim();
}

function extractArtists(moreInfo: any): string {
  // Try multiple artist fields JioSaavn returns
  const primary = moreInfo?.primary_artists ?? "";
  const singers = moreInfo?.singers ?? "";
  const musicComposers = moreInfo?.music ?? "";
  const artistMap = moreInfo?.artistMap?.primary_artists;
  if (Array.isArray(artistMap) && artistMap.length > 0) {
    return artistMap.map((a: any) => stripHtml(a.name ?? "")).filter(Boolean).join(", ");
  }
  const chosen = primary || singers || musicComposers || "";
  return stripHtml(chosen);
}

function normalizeItem(item: any): any | null {
  try {
    const moreInfo = item?.more_info ?? {};
    const title = stripHtml(item?.title ?? item?.song ?? "");
    const artist = extractArtists(moreInfo);
    const albumArt: string = (() => {
      const raw: string = item?.image ?? moreInfo?.album_url ?? "";
      // Upgrade to 500x500 from 150x150
      return raw.replace("150x150", "500x500").replace("50x50", "500x500");
    })();

    if (!title) return null;

    // songid for direct resolution; query fallback for StreamProviderChain
    const songId: string = item?.id ?? moreInfo?.song_id ?? "";
    const query = [title, artist].filter(Boolean).join(" ");

    // Duration
    const durationMs = moreInfo?.duration
      ? parseInt(String(moreInfo.duration), 10) * 1000
      : null;

    // Encrypted URL → optional direct stream hint
    let streamHint: string | null = null;
    const encUrl = moreInfo?.encrypted_media_url;
    if (encUrl && typeof encUrl === "string") {
      const dec = decryptMediaUrl(encUrl);
      if (dec) {
        const QUALITY_VARIANTS = ["_320.mp4", "_160.mp4", "_96.mp4"];
        const currentSuffix = QUALITY_VARIANTS.find((s) => dec.includes(s));
        streamHint = currentSuffix ? dec.replace(currentSuffix, "_320.mp4") : dec;
      }
    }

    return {
      title,
      artist,
      albumArt,
      songId,
      query,
      durationMs,
      streamHint,
    };
  } catch {
    return null;
  }
}

async function fetchTrending(): Promise<any[]> {
  const results: any[] = [];

  // Strategy 1: new_trending chart (most up-to-date viral tracks)
  try {
    const url = `${JIOSAAVN_API}?__call=content.getTrending&_format=json&_marker=0&api_version=4&ctx=web6dot0&entity_type=song&entity_language=hindi,punjabi,english&n=20&p=1`;
    const resp = await fetch(url, {
      headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
    });
    if (resp.ok) {
      const json = await resp.json().catch(() => null);
      const list = Array.isArray(json) ? json : (json?.results ?? json?.songs ?? []);
      for (const item of list) {
        const normalized = normalizeItem(item);
        if (normalized) results.push(normalized);
        if (results.length >= 20) break;
      }
    }
  } catch { /* continue to fallback */ }

  // Strategy 2: Weekly top charts — "new_trending" via modules
  if (results.length < 5) {
    try {
      const url = `${JIOSAAVN_API}?__call=webapi.getLaunchData&_format=json&_marker=0&api_version=4&ctx=web6dot0`;
      const resp = await fetch(url, {
        headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
      });
      if (resp.ok) {
        const json = await resp.json().catch(() => null);
        // new_trending is usually under json.new_trending or json.browse_discover
        const sections = [
          ...(json?.new_trending ?? []),
          ...(json?.top_playlists ?? []),
          ...(json?.browse_discover ?? []),
        ];
        for (const item of sections) {
          // If it's a song item
          if (item?.type === "song" || item?.more_info?.encrypted_media_url) {
            const normalized = normalizeItem(item);
            if (normalized && !results.some((r) => r.songId === normalized.songId)) {
              results.push(normalized);
            }
          }
          if (results.length >= 20) break;
        }
      }
    } catch { /* ignore */ }
  }

  // Strategy 3: Chartbuster search fallback for known hits
  if (results.length < 5) {
    const fallbackQueries = [
      "Diljit Dosanjh new song 2025",
      "Honey Singh hit song",
      "Bollywood trending 2025",
      "Arijit Singh new",
    ];
    for (const q of fallbackQueries) {
      if (results.length >= 15) break;
      try {
        const encoded = encodeURIComponent(q);
        const url = `${JIOSAAVN_API}?__call=search.getResults&_format=json&n=3&p=1&q=${encoded}&_marker=0&api_version=4&ctx=web6dot0`;
        const resp = await fetch(url, {
          headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
        });
        if (resp.ok) {
          const json = await resp.json().catch(() => null);
          const list = Array.isArray(json?.results) ? json.results : [];
          for (const item of list) {
            const normalized = normalizeItem(item);
            if (normalized && !results.some((r) => r.songId === normalized.songId)) {
              results.push(normalized);
            }
          }
        }
      } catch { /* ignore */ }
    }
  }

  return results.slice(0, 25);
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

  // Check in-memory edge cache
  const cacheKey = "trending_v1";
  const cached = cache.get(cacheKey);
  if (cached && Date.now() < cached.expiresAt) {
    return new Response(JSON.stringify({ success: true, tracks: cached.data, cached: true }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  try {
    const tracks = await fetchTrending();

    if (tracks.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No trending tracks found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const result = { success: true, tracks, cached: false, fetchedAt: Date.now() };
    cache.set(cacheKey, { data: tracks, expiresAt: Date.now() + CACHE_TTL_MS });

    return new Response(JSON.stringify(result), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    return new Response(
      JSON.stringify({ success: false, error: err.message ?? "Internal error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
