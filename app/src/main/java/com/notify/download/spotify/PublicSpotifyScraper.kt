package com.notify.download.spotify

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * PublicSpotifyScraper:
 * Extracts public Spotify playlist metadata without any user authentication or access tokens.
 * Requests the public embed/web HTML payload and parses track entries.
 * Strictly avoids logging cookies, tokens, or raw HTML payloads.
 */
class PublicSpotifyScraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "PublicSpotifyScraper"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private fun logD(msg: String) {
            try {
                android.util.Log.d(TAG, msg)
            } catch (_: Throwable) {
                // Safe for JVM unit test execution
            }
        }

        private fun logW(msg: String, throwable: Throwable? = null) {
            try {
                android.util.Log.w(TAG, msg, throwable)
            } catch (_: Throwable) {
                // Safe for JVM unit test execution
            }
        }

        private fun logE(msg: String, throwable: Throwable? = null) {
            try {
                android.util.Log.e(TAG, msg, throwable)
            } catch (_: Throwable) {
                // Safe for JVM unit test execution
            }
        }
    }

    /**
     * Scrapes a public Spotify playlist from its URL (or short link).
     * Returns a [ScrapeResult] with structured metadata, track list, and diagnostic metrics.
     */
    suspend fun scrapePlaylist(rawUrl: String): ScrapeResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Resolve spotify.link redirect safely (HTTPS only, max 3 redirects, final host open.spotify.com)
        val resolved = SpotifyUrlResolver.resolveAndClassify(rawUrl)
        val playlistId = when (resolved) {
            is ResolvedUrl.SpotifyPlaylist -> resolved.playlistId
            else -> {
                val elapsed = System.currentTimeMillis() - startTime
                return@withContext ScrapeResult(
                    playlist = null,
                    diagnostics = ScrapeDiagnostics(
                        httpStatusCode = 0,
                        finalHost = "invalid",
                        contentType = null,
                        payloadSizeBytes = 0L,
                        parserStrategy = "NONE",
                        elapsedTimeMs = elapsed,
                        isComplete = false,
                        warningMessage = "URL is not a valid Spotify playlist"
                    ),
                    error = "Invalid Spotify playlist URL: $rawUrl"
                )
            }
        }

        val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
        logD("Requesting public Spotify embed page for playlist $playlistId...")

        try {
            // Attempt 1: Embed page (typically smaller, clean JSON payload)
            val embedResult = requestAndParse(
                targetUrl = embedUrl,
                playlistId = playlistId,
                startTime = startTime,
                isEmbed = true
            )

            // If embed succeeded and found tracks, return result
            if (embedResult.playlist != null && embedResult.playlist.tracks.isNotEmpty()) {
                return@withContext embedResult
            }

            // Attempt 2: Direct web page fallback if embed didn't yield tracks
            logD("Embed returned no tracks, attempting web playlist fallback...")
            val webUrl = "https://open.spotify.com/playlist/$playlistId"
            val webResult = requestAndParse(
                targetUrl = webUrl,
                playlistId = playlistId,
                startTime = startTime,
                isEmbed = false
            )

            if (webResult.playlist != null && webResult.playlist.tracks.isNotEmpty()) {
                return@withContext webResult
            }

            // If both failed, return the more informative diagnostic
            return@withContext if (embedResult.diagnostics.httpStatusCode != 0) embedResult else webResult

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            logE("Network or parsing error during scrape", e)
            ScrapeResult(
                playlist = null,
                diagnostics = ScrapeDiagnostics(
                    httpStatusCode = 0,
                    finalHost = "open.spotify.com",
                    contentType = null,
                    payloadSizeBytes = 0L,
                    parserStrategy = "EXCEPTION",
                    elapsedTimeMs = elapsed,
                    isComplete = false,
                    warningMessage = e.message
                ),
                error = "Exception: ${e.message}"
            )
        }
    }

    private fun requestAndParse(
        targetUrl: String,
        playlistId: String,
        startTime: Long,
        isEmbed: Boolean
    ): ScrapeResult {
        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            val elapsed = System.currentTimeMillis() - startTime
            val statusCode = response.code
            val finalHost = response.request.url.host
            val contentType = response.header("Content-Type")
            val responseBody = response.body?.string() ?: ""
            val payloadSize = responseBody.toByteArray().size.toLong()

            if (!response.isSuccessful) {
                return ScrapeResult(
                    playlist = null,
                    diagnostics = ScrapeDiagnostics(
                        httpStatusCode = statusCode,
                        finalHost = finalHost,
                        contentType = contentType,
                        payloadSizeBytes = payloadSize,
                        parserStrategy = if (isEmbed) "FAILED_EMBED_HTTP" else "FAILED_WEB_HTTP",
                        elapsedTimeMs = elapsed,
                        isComplete = false,
                        warningMessage = "HTTP $statusCode returned from Spotify ($finalHost)"
                    ),
                    error = "HTTP error $statusCode: ${response.message}"
                )
            }

            // Parse HTML using cascading strategies
            val parseResult = parseHtml(playlistId, responseBody, isEmbed)

            val isComplete = parseResult.playlist?.isComplete ?: false
            val warning = when {
                parseResult.playlist == null -> "Could not parse playlist tracks from ${if (isEmbed) "embed" else "web"} HTML"
                !isComplete -> "Incomplete extraction: Found ${parseResult.playlist.extractedTrackCount} of ${parseResult.playlist.expectedTrackCount} tracks (Spotify embed/web pages paginate long playlists)."
                else -> null
            }

            val diagnostics = ScrapeDiagnostics(
                httpStatusCode = statusCode,
                finalHost = finalHost,
                contentType = contentType,
                payloadSizeBytes = payloadSize,
                parserStrategy = parseResult.strategyUsed,
                elapsedTimeMs = elapsed,
                isComplete = isComplete,
                warningMessage = warning
            )

            logD("Scrape finished in ${elapsed}ms: status=$statusCode, tracks=${parseResult.playlist?.extractedTrackCount ?: 0}, strategy=${parseResult.strategyUsed}")

            return ScrapeResult(
                playlist = parseResult.playlist,
                diagnostics = diagnostics,
                error = if (parseResult.playlist == null) "No tracks extracted" else null
            )
        }
    }

    internal data class IntermediateParse(
        val playlist: ScrapedPlaylist?,
        val strategyUsed: String
    )

    internal fun parseHtml(playlistId: String, html: String, isEmbed: Boolean = true): IntermediateParse {
        // Strategy 1: Look for <script id="__NEXT_DATA__" type="application/json">
        val nextDataRegex = Regex("""<script\s+id="__NEXT_DATA__"\s+type="application/json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val nextDataMatch = nextDataRegex.find(html)

        if (nextDataMatch != null) {
            val jsonString = nextDataMatch.groupValues[1].trim()
            try {
                val json = JSONObject(jsonString)
                val playlist = parseNextDataJson(playlistId, json)
                if (playlist != null && playlist.tracks.isNotEmpty()) {
                    return IntermediateParse(playlist, if (isEmbed) "EMBED_NEXT_DATA" else "WEB_NEXT_DATA")
                }
            } catch (e: Exception) {
                logW("Failed to parse __NEXT_DATA__ JSON", e)
            }
        }

        // Strategy 2: Look for <script id="initial-data" ...> (base64 or raw JSON)
        val initialDataRegex = Regex("""<script\s+id="initial-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val initialDataMatch = initialDataRegex.find(html)
        if (initialDataMatch != null) {
            val content = initialDataMatch.groupValues[1].trim()
            try {
                val jsonStr = try {
                    String(Base64.getDecoder().decode(content))
                } catch (_: Exception) {
                    content
                }
                val json = JSONObject(jsonStr)
                val playlist = parseNextDataJson(playlistId, json)
                if (playlist != null && playlist.tracks.isNotEmpty()) {
                    return IntermediateParse(playlist, "EMBED_INITIAL_DATA")
                }
            } catch (e: Exception) {
                logW("Failed to parse initial-data JSON", e)
            }
        }

        // Strategy 3: Regex JSON search for "trackList"
        val trackListRegex = Regex(""""trackList":\s*(\[.*?\])\s*,\s*"totalTracks":\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)
        val trackListMatch = trackListRegex.find(html)
        if (trackListMatch != null) {
            try {
                val arrayStr = trackListMatch.groupValues[1]
                val totalTracks = trackListMatch.groupValues[2].toIntOrNull()
                val jsonArray = JSONArray(arrayStr)
                val tracks = parseTrackListArray(jsonArray)

                val titleRegex = Regex("""<title>(.*?)</title>""")
                val rawTitle = titleRegex.find(html)?.groupValues?.get(1)?.substringBefore("|")?.substringBefore("-")?.trim() ?: "Spotify Playlist"

                if (tracks.isNotEmpty()) {
                    return IntermediateParse(
                        ScrapedPlaylist(
                            id = playlistId,
                            title = rawTitle,
                            description = null,
                            artworkUrl = null,
                            expectedTrackCount = totalTracks ?: tracks.size,
                            tracks = tracks
                        ),
                        "EMBED_REGEX_JSON"
                    )
                }
            } catch (e: Exception) {
                logW("Failed to parse trackList regex array", e)
            }
        }

        // Strategy 4: Generic JSON array regex search for any [ { "title" / "name" , "artists" / "subtitle" ... } ]
        val genericTrackRegex = Regex(""""trackList":\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val genericMatch = genericTrackRegex.find(html)
        if (genericMatch != null) {
            try {
                val arrayStr = genericMatch.groupValues[1]
                val jsonArray = JSONArray(arrayStr)
                val tracks = parseTrackListArray(jsonArray)
                if (tracks.isNotEmpty()) {
                    return IntermediateParse(
                        ScrapedPlaylist(
                            id = playlistId,
                            title = "Spotify Playlist",
                            description = null,
                            artworkUrl = null,
                            expectedTrackCount = tracks.size,
                            tracks = tracks
                        ),
                        "GENERIC_REGEX_JSON"
                    )
                }
            } catch (e: Exception) {
                logW("Failed to parse generic trackList regex", e)
            }
        }

        return IntermediateParse(null, "NO_PARSE_MATCH")
    }

    private fun parseNextDataJson(playlistId: String, root: JSONObject): ScrapedPlaylist? {
        val props = root.optJSONObject("props") ?: return null
        val pageProps = props.optJSONObject("pageProps") ?: return null

        // Try different structure variations Spotify has used in Next.js props
        val state = pageProps.optJSONObject("state")
        val data = state?.optJSONObject("data")
        val entity = data?.optJSONObject("entity") ?: pageProps.optJSONObject("entity")

        val title = entity?.optString("name", entity.optString("title", "Spotify Playlist"))
            ?: pageProps.optJSONObject("metadata")?.optString("title", "Spotify Playlist")
            ?: "Spotify Playlist"

        val description = entity?.optString("description")?.takeIf { it.isNotBlank() }
        val artworkUrl = entity?.optString("coverArtUrl")?.takeIf { it.isNotBlank() }
            ?: entity?.optString("artworkUrl")?.takeIf { it.isNotBlank() }
            ?: entity?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: entity?.optJSONObject("visualIdentity")?.optJSONArray("image")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: data?.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: pageProps.optJSONObject("metadata")?.optString("coverArtUrl")?.takeIf { it.isNotBlank() }

        // Extract trackList array
        val trackList = data?.optJSONArray("trackList")
            ?: entity?.optJSONArray("trackList")
            ?: pageProps.optJSONArray("trackList")
            ?: entity?.optJSONObject("tracks")?.optJSONArray("items")

        if (trackList == null) return null

        val expectedTotal = entity?.optInt("totalTracks", trackList.length()) ?: trackList.length()
        val tracks = parseTrackListArray(trackList)

        return ScrapedPlaylist(
            id = playlistId,
            title = title,
            description = description,
            artworkUrl = artworkUrl,
            expectedTrackCount = expectedTotal,
            tracks = tracks
        )
    }

    private fun parseTrackListArray(array: JSONArray): List<ScrapedTrack> {
        val list = mutableListOf<ScrapedTrack>()
        for (i in 0 until array.length()) {
            val rawItem = array.optJSONObject(i) ?: continue
            val item = rawItem.optJSONObject("track") ?: rawItem

            val title = item.optString("title", item.optString("name", ""))
            val subtitle = item.optString("subtitle", "")
            val artistsArray = item.optJSONArray("artists")

            val artist = when {
                subtitle.isNotBlank() -> subtitle
                artistsArray != null && artistsArray.length() > 0 -> {
                    val artistsList = mutableListOf<String>()
                    for (j in 0 until artistsArray.length()) {
                        val a = artistsArray.optJSONObject(j)?.optString("name")
                        if (!a.isNullOrBlank()) artistsList.add(a)
                    }
                    if (artistsList.isNotEmpty()) artistsList.joinToString(", ") else "Unknown Artist"
                }
                item.optJSONObject("artist")?.optString("name") != null -> {
                    item.optJSONObject("artist")!!.optString("name")
                }
                item.optString("artist").isNotBlank() -> item.optString("artist")
                else -> "Unknown Artist"
            }

            val durationMs = item.optLong("duration", item.optLong("duration_ms", item.optLong("durationMs", 0L)))
            val album = item.optJSONObject("album")?.optString("name")
                ?: item.optString("album").takeIf { it.isNotBlank() }

            // Extract highest-resolution per-track artwork from all known Spotify embed paths.
            // Never falls back to playlist-level artwork — returns null if nothing found.
            val artworkUrl = extractBestArtworkUrl(item)

            if (title.isNotBlank()) {
                list.add(
                    ScrapedTrack(
                        position = i + 1,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        artworkUrl = artworkUrl
                    )
                )
            }
        }
        return list
    }

    /**
     * Extracts the highest-resolution per-track artwork URL from a Spotify embed track JSON object.
     * Checks all known paths in priority order; returns null if no track-specific art is found.
     * NEVER reads from a playlist-level field.
     */
    private fun extractBestArtworkUrl(item: JSONObject): String? {
        // 1. item.album.images[] — standard Web API / embed format; pick highest resolution
        val albumImages = item.optJSONObject("album")?.optJSONArray("images")
        val bestFromAlbumImages = pickBestImageUrl(albumImages)
        if (!bestFromAlbumImages.isNullOrBlank()) return bestFromAlbumImages

        // 2. item.album.coverArt.sources[] — embed format variant
        val albumCoverArtSources = item.optJSONObject("album")
            ?.optJSONObject("coverArt")
            ?.optJSONArray("sources")
        val bestFromAlbumCoverArt = pickBestImageUrl(albumCoverArtSources)
        if (!bestFromAlbumCoverArt.isNullOrBlank()) return bestFromAlbumCoverArt

        // 3. item.album.coverArtUrl — plain string field
        val albumCoverArtUrl = item.optJSONObject("album")?.optString("coverArtUrl")?.takeIf { it.isNotBlank() }
        if (!albumCoverArtUrl.isNullOrBlank()) return albumCoverArtUrl

        // 4. item.coverArt.sources[] — track-level coverArt (some embed variants)
        val itemCoverArtSources = item.optJSONObject("coverArt")?.optJSONArray("sources")
        val bestFromItemCoverArt = pickBestImageUrl(itemCoverArtSources)
        if (!bestFromItemCoverArt.isNullOrBlank()) return bestFromItemCoverArt

        // 5. item.artworkUrl — explicit field (used in some Spotify embed payloads)
        val itemArtworkUrl = item.optString("artworkUrl").takeIf { it.isNotBlank() }
        if (!itemArtworkUrl.isNullOrBlank()) return itemArtworkUrl

        // 6. item.images[] — top-level images array on the track object
        val itemImages = item.optJSONArray("images")
        val bestFromItemImages = pickBestImageUrl(itemImages)
        if (!bestFromItemImages.isNullOrBlank()) return bestFromItemImages

        return null
    }

    /**
     * Picks the best (highest-resolution) image URL from a JSON array containing objects with
     * `url` (required), and optionally `width` and `height` fields.
     * If dimensions are absent, prefers the first entry (usually highest quality in Spotify).
     */
    private fun pickBestImageUrl(images: JSONArray?): String? {
        if (images == null || images.length() == 0) return null

        data class ImageEntry(val url: String, val width: Int, val height: Int)

        val entries = mutableListOf<ImageEntry>()
        for (i in 0 until images.length()) {
            val obj = images.optJSONObject(i) ?: continue
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            val w = obj.optInt("width", 0)
            val h = obj.optInt("height", 0)
            entries.add(ImageEntry(url, w, h))
        }
        if (entries.isEmpty()) return null

        // If dimensions are available, pick the entry with the largest area.
        // Otherwise, use the first entry (Spotify orders highest-res first).
        val hasDimensions = entries.any { it.width > 0 && it.height > 0 }
        return if (hasDimensions) {
            entries.maxByOrNull { it.width * it.height }?.url
        } else {
            entries.first().url
        }
    }
}
