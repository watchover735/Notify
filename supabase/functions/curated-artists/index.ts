import CryptoJS from "npm:crypto-js@4.2.0";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

const JIOSAAVN_API = "https://www.jiosaavn.com/api.php";
const LEGACY_DES_KEY = "38346591";

// In-memory cache per individual artist (2 hour TTL)
// Allows incremental fetches: new artist = 1 JioSaavn fetch, existing artists served from cache
const artistCache = new Map<string, { data: any; expiresAt: number }>();
const ARTIST_CACHE_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours

const DEFAULT_CURATED_ARTISTS = [
  { name: "Yo Yo Honey Singh", id: "485956" },
  { name: "Diljit Dosanjh", id: "468245" },
  { name: "Arijit Singh", id: "459320" },
  { name: "Karan Aujla", id: "697691" },
  { name: "AP Dhillon", id: "681966" },
];

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
      return raw.replace("150x150", "500x500").replace("50x50", "500x500");
    })();

    if (!title) return null;

    const songId: string = item?.id ?? moreInfo?.song_id ?? "";
    const query = [title, artist].filter(Boolean).join(" ");

    const durationMs = moreInfo?.duration
      ? parseInt(String(moreInfo.duration), 10) * 1000
      : null;

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

async function fetchArtistSection(artist: { name: string; id?: string }): Promise<any | null> {
  try {
    let artistId = artist.id;
    let artistName = artist.name;
    let artistImage = "";

    // Search for artist ID if not specified
    if (!artistId) {
      const searchUrl = `${JIOSAAVN_API}?__call=search.getArtistResults&q=${encodeURIComponent(artist.name)}&_format=json&_marker=0&ctx=web6dot0&api_version=4&n=1`;
      const searchResp = await fetch(searchUrl, {
        headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
      });
      if (searchResp.ok) {
        const searchJson = await searchResp.json().catch(() => null);
        const first = searchJson?.results?.[0];
        if (first?.id) {
          artistId = first.id;
          artistName = first.name || artistName;
          artistImage = first.image || "";
        }
      }
    }

    if (!artistId) return null;

    // Fetch artist details & top songs
    const detailsUrl = `${JIOSAAVN_API}?__call=artist.getArtistPageDetails&artistId=${artistId}&_format=json&_marker=0&ctx=web6dot0&api_version=4&n_song=15`;
    const detailsResp = await fetch(detailsUrl, {
      headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" },
    });

    if (!detailsResp.ok) return null;

    const json = await detailsResp.json().catch(() => null);
    if (!json) return null;

    artistName = stripHtml(json.name || artistName);
    const rawImg: string = json.image || artistImage;
    artistImage = rawImg.replace("150x150", "500x500").replace("50x50", "500x500");

    const rawSongs = Array.isArray(json.topSongs) ? json.topSongs : [];
    const tracks: any[] = [];
    for (const song of rawSongs) {
      const normalized = normalizeItem(song);
      if (normalized && !tracks.some((t) => t.songId === normalized.songId)) {
        tracks.push(normalized);
      }
      if (tracks.length >= 15) break;
    }

    if (tracks.length === 0) return null;

    return {
      artistId,
      name: artistName,
      image: artistImage,
      tracks,
    };
  } catch (err) {
    console.error(`Error fetching artist ${artist.name}:`, err);
    return null;
  }
}

async function getArtistSectionWithCache(artist: { name: string; id?: string }): Promise<any | null> {
  const cacheKey = artist.id ? `id_${artist.id}` : `name_${artist.name.trim().toLowerCase()}`;
  const cached = artistCache.get(cacheKey);
  if (cached && Date.now() < cached.expiresAt) {
    return cached.data;
  }

  const section = await fetchArtistSection(artist);
  if (section) {
    const entry = { data: section, expiresAt: Date.now() + ARTIST_CACHE_TTL_MS };
    artistCache.set(cacheKey, entry);
    if (section.artistId && `id_${section.artistId}` !== cacheKey) {
      artistCache.set(`id_${section.artistId}`, entry);
    }
    if (section.name) {
      artistCache.set(`name_${section.name.trim().toLowerCase()}`, entry);
    }
  }
  return section;
}

async function fetchAllCuratedArtists(artists = DEFAULT_CURATED_ARTISTS): Promise<any[]> {
  const promises = artists.map((a) => getArtistSectionWithCache(a));
  const results = await Promise.allSettled(promises);
  const sections: any[] = [];
  for (const r of results) {
    if (r.status === "fulfilled" && r.value) {
      sections.push(r.value);
    }
  }
  return sections;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  // Parse custom artists from body if POST
  let requestedArtists = DEFAULT_CURATED_ARTISTS;
  if (req.method === "POST") {
    try {
      const body = await req.json();
      if (Array.isArray(body?.artists) && body.artists.length > 0) {
        requestedArtists = body.artists.map((item: any) => {
          if (typeof item === "string") return { name: item };
          return { name: item.name, id: item.id };
        });
      }
    } catch { /* use default */ }
  }

  try {
    const sections = await fetchAllCuratedArtists(requestedArtists);

    if (sections.length === 0) {
      return new Response(
        JSON.stringify({ success: false, error: "No artist sections found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const result = { success: true, sections, fetchedAt: Date.now() };

    return new Response(JSON.stringify(result), {
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json",
        "Cache-Control": "public, max-age=7200, s-maxage=7200",
      },
    });
  } catch (err: any) {
    return new Response(
      JSON.stringify({ success: false, error: err.message ?? "Internal error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
