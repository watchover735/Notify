package com.notify.core.playback

import java.util.regex.Pattern

/**
 * Centralized artwork resolution engine providing high-definition, sharp image URLs
 * across all music providers with resilient fallback chains.
 */
object ArtworkResolution {

    private val JIOSAAVN_PATTERN = Pattern.compile("https?://[^/]*(?:saavncdn\\.com|jiosaavn\\.com)/.*")
    private val DEEZER_PATTERN = Pattern.compile("https?://[^/]*(?:dzcdn\\.net|deezer\\.com)/.*")
    private val SOUNDCLOUD_PATTERN = Pattern.compile("https?://[^/]*sndcdn\\.com/.*")
    private val YOUTUBE_PATTERN = Pattern.compile("https?://(?:[^/]*ytimg\\.com|img\\.youtube\\.com|[^/]*youtube\\.com)/(?:vi|vi_webp)/([^/?#]+)/?.*")
    private val GOOGLE_USERCONTENT_PATTERN = Pattern.compile("https?://[^/]*googleusercontent\\.com/.*")

    /**
     * Upgrades a given artwork URL to its best high-resolution version.
     * Guaranteed to never throw and returns the original URL for unknown hosts.
     *
     * @param url Original artwork URL
     * @param targetPx Requested target dimension in pixels
     */
    fun highResArtwork(url: String?, targetPx: Int = 512): String? {
        if (url.isNullOrBlank()) return null
        return highResArtworkCandidates(url, targetPx).firstOrNull() ?: url
    }

    /**
     * Returns an ordered list of candidate URLs from highest to lowest resolution.
     * Callers or image loaders can attempt each candidate sequentially to avoid 404s
     * (e.g. for YouTube tracks without maxresdefault).
     */
    fun highResArtworkCandidates(url: String?, targetPx: Int = 512): List<String> {
        if (url.isNullOrBlank()) return emptyList()

        // Local storage and content URIs are returned as-is
        if (url.startsWith("content://") || url.startsWith("file://") || url.startsWith("/")) {
            return listOf(url)
        }

        // 1. JioSaavn: 50x50 and 150x150 tokens upgraded to 500x500 (max reliable size)
        if (JIOSAAVN_PATTERN.matcher(url).matches()) {
            val upgraded = url.replace("50x50.jpg", "500x500.jpg")
                .replace("150x150.jpg", "500x500.jpg")
                .replace("-50x50.", "-500x500.")
                .replace("-150x150.", "-500x500.")
            return if (upgraded != url) listOf(upgraded, url) else listOf(url)
        }

        // 2. Deezer: cover_small / cover_medium / size segment upgraded to 1000x1000 or 500x500
        if (DEEZER_PATTERN.matcher(url).matches()) {
            val isLarge = targetPx > 500
            val primarySize = if (isLarge) "1000x1000" else "500x500"
            val secondarySize = "500x500"

            val upgraded1 = url.replace("cover_small", if (isLarge) "cover_xl" else "cover_big")
                .replace("cover_medium", if (isLarge) "cover_xl" else "cover_big")
                .replace(Regex("/\\d+x\\d+-"), "/$primarySize-")
                .replace(Regex("/\\d+x\\d+/"), "/$primarySize/")

            val upgraded2 = url.replace("cover_small", "cover_big")
                .replace("cover_medium", "cover_big")
                .replace(Regex("/\\d+x\\d+-"), "/$secondarySize-")
                .replace(Regex("/\\d+x\\d+/"), "/$secondarySize/")

            val candidates = mutableListOf<String>()
            candidates.add(upgraded1)
            if (upgraded2 != upgraded1) candidates.add(upgraded2)
            if (url !in candidates) candidates.add(url)
            return candidates
        }

        // 3. SoundCloud: -large replaced with -t500x500
        if (SOUNDCLOUD_PATTERN.matcher(url).matches()) {
            val upgraded = url.replace("-large.", "-t500x500.")
                .replace("-small.", "-t500x500.")
                .replace("-badge.", "-t500x500.")
                .replace("-t300x300.", "-t500x500.")
            return if (upgraded != url) listOf(upgraded, url) else listOf(url)
        }

        // 4. YouTube: prefer maxresdefault.jpg, fall back to sddefault.jpg, then hqdefault.jpg
        val ytMatcher = YOUTUBE_PATTERN.matcher(url)
        if (ytMatcher.matches()) {
            val videoId = ytMatcher.group(1)
            if (!videoId.isNullOrBlank()) {
                val candidates = mutableListOf<String>()
                // Prefer maxresdefault for large targets, sddefault/hqdefault as reliable fallbacks
                candidates.add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                candidates.add("https://i.ytimg.com/vi/$videoId/sddefault.jpg")
                candidates.add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                if (url !in candidates) {
                    candidates.add(url)
                }
                return candidates
            }
        }

        // 5. Google / YouTube Music: upgrade =wXX-hXX or =sXX params to =w544-h544
        if (GOOGLE_USERCONTENT_PATTERN.matcher(url).matches()) {
            val upgraded = url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
                .replace(Regex("=s\\d+"), "=s544")
            return if (upgraded != url) listOf(upgraded, url) else listOf(url)
        }

        // 6. Unknown host: return original URL unmodified
        return listOf(url)
    }
}
