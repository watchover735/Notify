package com.notify.download.spotify

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ResolvedUrl {
    data class SpotifyTrack(val trackId: String, val cleanUrl: String) : ResolvedUrl()
    data class SpotifyPlaylist(val playlistId: String, val cleanUrl: String) : ResolvedUrl()
    data class SpotifyAlbum(val albumId: String, val cleanUrl: String) : ResolvedUrl()
    data class YouTube(val canonicalUrl: String) : ResolvedUrl()
    data class Unsupported(val reason: String) : ResolvedUrl()
}

/**
 * Resolves and classifies input URLs:
 * - Follows spotify.link redirects securely (HTTPS only, max 3 redirects, final host must be open.spotify.com).
 * - Enforces that open.spotify.com URLs are never routed directly to yt-dlp.
 * - Extracts clean track/playlist/album IDs.
 * - Normalizes YouTube / YouTube Music URLs.
 */
object SpotifyUrlResolver {

    private const val MAX_REDIRECTS = 3

    suspend fun resolveAndClassify(rawUrl: String): ResolvedUrl = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return@withContext ResolvedUrl.Unsupported("URL is empty")
        }

        try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: ""
            if (scheme != "http" && scheme != "https") {
                return@withContext ResolvedUrl.Unsupported("Invalid URL scheme: $scheme")
            }

            val host = uri.host?.lowercase() ?: ""

            // 1. Check for spotify.link short URLs and resolve redirect safely
            val targetUri = if (host == "spotify.link" || host.endsWith(".spotify.link")) {
                resolveSpotifyLinkRedirect(trimmed) ?: return@withContext ResolvedUrl.Unsupported(
                    "Failed to securely resolve spotify.link redirect to open.spotify.com"
                )
            } else {
                uri
            }

            val finalHost = targetUri.host?.lowercase() ?: ""
            val finalPath = targetUri.path ?: ""

            // 2. Classify Spotify URLs
            if (finalHost == "open.spotify.com") {
                val segments = finalPath.split("/").filter { it.isNotBlank() }
                // e.g., ["track", "4cOdK2wGLETKBW3PvgPWqT"] or ["intl-en", "track", "4cOdK2wGLETKBW3PvgPWqT"]
                val typeIndex = segments.indexOfFirst { it in listOf("track", "playlist", "album") }
                if (typeIndex != -1 && typeIndex + 1 < segments.size) {
                    val type = segments[typeIndex]
                    val id = segments[typeIndex + 1].substringBefore("?")
                    return@withContext when (type) {
                        "track" -> ResolvedUrl.SpotifyTrack(id, "https://open.spotify.com/track/$id")
                        "playlist" -> ResolvedUrl.SpotifyPlaylist(id, "https://open.spotify.com/playlist/$id")
                        "album" -> ResolvedUrl.SpotifyAlbum(id, "https://open.spotify.com/album/$id")
                        else -> ResolvedUrl.Unsupported("Unsupported Spotify entity: $type")
                    }
                }
                return@withContext ResolvedUrl.Unsupported("Could not parse Spotify ID from path: $finalPath")
            }

            // 3. Classify YouTube / YouTube Music URLs
            if (finalHost in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) {
                val canonicalUrl = normalizeYouTubeUrl(targetUri)
                return@withContext if (canonicalUrl != null) {
                    ResolvedUrl.YouTube(canonicalUrl)
                } else {
                    ResolvedUrl.Unsupported("Could not extract YouTube video ID from URL")
                }
            }

            ResolvedUrl.Unsupported("Unsupported host: $finalHost. Please enter a Spotify or YouTube URL.")
        } catch (e: Exception) {
            ResolvedUrl.Unsupported("Malformed URL: ${e.message}")
        }
    }

    /**
     * Follows spotify.link redirects with strict security constraints:
     * - HTTPS only
     * - Maximum 3 redirects
     * - Final host must be open.spotify.com
     */
    private fun resolveSpotifyLinkRedirect(startUrl: String): URI? {
        var currentUrl = startUrl
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            val url = URL(currentUrl)
            if (url.protocol.lowercase() != "https") {
                return null // Enforce HTTPS only
            }

            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "NotiFy-Android/1.0")
            }

            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    val redirectUri = URI(location)
                    val nextUrl = if (redirectUri.isAbsolute) {
                        location
                    } else {
                        URI(url.protocol, url.host, location, null).toString()
                    }
                    currentUrl = nextUrl
                    redirectCount++
                } else if (responseCode in 200..299) {
                    val finalUri = URI(currentUrl)
                    val finalHost = finalUri.host?.lowercase() ?: ""
                    return if (finalHost == "open.spotify.com") finalUri else null
                } else {
                    return null
                }
            } finally {
                connection.disconnect()
            }
        }

        // Check if final URL reached open.spotify.com
        val finalUri = URI(currentUrl)
        return if (finalUri.host?.lowercase() == "open.spotify.com") finalUri else null
    }

    /**
     * Normalizes YouTube URLs to canonical https://www.youtube.com/watch?v={id}
     */
    private fun normalizeYouTubeUrl(uri: URI): String? {
        val host = uri.host?.lowercase() ?: ""
        return when {
            host == "youtu.be" -> {
                val videoId = uri.path?.trimStart('/')?.substringBefore('?')
                if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else null
            }
            host.contains("youtube.com") -> {
                val query = uri.query ?: ""
                val params = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (parts.getOrNull(1) ?: "")
                }
                val videoId = params["v"]
                if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else null
            }
            else -> null
        }
    }
}
