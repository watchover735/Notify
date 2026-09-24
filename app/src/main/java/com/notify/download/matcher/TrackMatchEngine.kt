package com.notify.download.matcher

import com.notify.download.spotify.SpotifyTrackMetadata
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.exp

/**
 * SpotDL-inspired matching engine:
 * Evaluates candidate YouTube videos against Spotify track metadata using:
 * - Text normalization (stripping junk tags like (Official Video), [Lyrics], 4K, HD).
 * - Artist overlap (primary artist + featuring artists).
 * - Duration curve: exp(-abs(delta) / 8000.0).
 * - Forbidden word filter (karaoke, cover, slowed, reverb, remix, live, instrumental).
 * - Topic channel preference.
 * - Always produces canonical download URL: https://www.youtube.com/watch?v={videoId}.
 */
object TrackMatchEngine {

    val FORBIDDEN_WORDS = setOf(
        "karaoke", "cover", "slowed", "reverb", "remix", "live",
        "instrumental", "acoustic", "bassboost", "8d audio", "tribute"
    )

    private val JUNK_PATTERNS = listOf(
        Regex("""(?i)\(official\s*(music)?\s*(video|audio)?\)"""),
        Regex("""(?i)\[official\s*(music)?\s*(video|audio)?\]"""),
        Regex("""(?i)\(lyrics?\s*(video)?\)"""),
        Regex("""(?i)\[lyrics?\s*(video)?\]"""),
        Regex("""(?i)\(visualizer\)"""),
        Regex("""(?i)\[visualizer\]"""),
        Regex("""(?i)\b(hd|4k|uhd|1080p|hq|audio)\b"""),
        Regex("""(?i)[\(\[\{].*?[\)\]\}]""") // remaining parenthesized noise
    )

    fun scoreCandidate(
        spotifyTrack: SpotifyTrackMetadata,
        candidate: YouTubeCandidate
    ): Float {
        val normTrackTitle = normalizeText(spotifyTrack.title)
        val normCandidateTitle = normalizeText(candidate.title)
        val normChannel = normalizeText(candidate.channelTitle ?: "")

        // 1. Forbidden words check
        var forbiddenPenalty = 0.0f
        for (word in FORBIDDEN_WORDS) {
            val candidateHasWord = normCandidateTitle.contains(word) || normChannel.contains(word)
            val spotifyHasWord = normTrackTitle.contains(word) || normalizeText(spotifyTrack.album ?: "").contains(word)
            if (candidateHasWord && !spotifyHasWord) {
                forbiddenPenalty += 0.60f
            }
        }

        // 2. Duration score curve
        val deltaMs = abs(candidate.durationMs - spotifyTrack.durationMs)
        val durationScore = if (spotifyTrack.durationMs > 0L) {
            exp(-deltaMs.toDouble() / 8000.0).toFloat().coerceIn(0.0f, 1.0f)
        } else {
            0.5f
        }

        // 3. Title token overlap
        val trackTokens = tokenize(normTrackTitle)
        val candidateTokens = tokenize(normCandidateTitle)
        val titleMatchRatio = if (trackTokens.isNotEmpty()) {
            val matches = trackTokens.count { candidateTokens.contains(it) }
            matches.toFloat() / trackTokens.size.toFloat()
        } else {
            0.0f
        }

        // 4. Artist match
        val primaryArtistNorm = normalizeText(spotifyTrack.primaryArtist)
        val artistInCandidate = normCandidateTitle.contains(primaryArtistNorm) || normChannel.contains(primaryArtistNorm)
        val artistScore = if (artistInCandidate) 1.0f else 0.3f

        // 5. Official / Topic channel bonus
        val isTopicChannel = normChannel.endsWith("topic") || normChannel.contains("official")
        val channelBonus = if (isTopicChannel) 0.15f else 0.0f

        // Combined Weighted Score
        val totalScore = (titleMatchRatio * 0.45f) + (durationScore * 0.35f) + (artistScore * 0.20f) + channelBonus - forbiddenPenalty
        return totalScore.coerceIn(0.0f, 1.0f)
    }

    fun findBestMatch(
        spotifyTrack: SpotifyTrackMetadata,
        candidates: List<YouTubeCandidate>
    ): MatchResult? {
        if (candidates.isEmpty()) return null

        var bestCandidate = candidates.first()
        var highestScore = -1.0f

        for (candidate in candidates) {
            val score = scoreCandidate(spotifyTrack, candidate)
            if (score > highestScore) {
                highestScore = score
                bestCandidate = candidate
            }
        }

        val deltaMs = abs(bestCandidate.durationMs - spotifyTrack.durationMs)
        val isConfident = highestScore >= 0.60f && deltaMs < 20_000L

        return MatchResult(
            candidate = bestCandidate,
            // CRITICAL: yt-dlp receives strictly www.youtube.com, never music.youtube.com
            canonicalDownloadUrl = "https://www.youtube.com/watch?v=${bestCandidate.videoId}",
            matchScore = highestScore,
            durationDeltaMs = deltaMs,
            isConfident = isConfident
        )
    }

    /**
     * Stricter confidence gate specifically for background artwork enrichment.
     * Prevents assigning wrong video thumbnails to songs:
     * - Requires isConfident and matchScore >= 0.70f (target 0.70-0.75)
     * - Strong normalized title match (token overlap >= 0.60)
     * - Strong primary artist match
     * - Reasonable duration difference (delta <= 15_000ms when duration is known)
     */
    fun isSafeEnrichmentMatch(
        spotifyTrack: SpotifyTrackMetadata,
        candidate: YouTubeCandidate,
        matchResult: MatchResult?
    ): Boolean {
        if (matchResult == null || !matchResult.isConfident) return false
        if (matchResult.matchScore < 0.70f) return false

        val normTrackTitle = normalizeText(spotifyTrack.title)
        val normCandidateTitle = normalizeText(candidate.title)
        val normPrimaryArtist = normalizeText(spotifyTrack.primaryArtist)
        val normChannel = normalizeText(candidate.channelTitle ?: "")

        // 1. Strong title match: track tokens must overlap significantly
        val trackTokens = tokenize(normTrackTitle)
        val candidateTokens = tokenize(normCandidateTitle)
        if (trackTokens.isEmpty()) return false
        val matchedTokens = trackTokens.count { candidateTokens.contains(it) }
        val titleRatio = matchedTokens.toFloat() / trackTokens.size.toFloat()
        if (titleRatio < 0.60f) return false

        // 2. Strong primary artist match
        val artistMatches = normCandidateTitle.contains(normPrimaryArtist) ||
                normChannel.contains(normPrimaryArtist)
        if (!artistMatches && normPrimaryArtist != "unknown artist") {
            return false
        }

        // 3. Reasonable duration difference when duration exists
        if (spotifyTrack.durationMs > 0L && candidate.durationMs > 0L) {
            val deltaMs = abs(candidate.durationMs - spotifyTrack.durationMs)
            if (deltaMs > 15_000L) {
                return false
            }
        }

        return true
    }

    fun normalizeText(input: String): String {
        var cleaned = input
        for (pattern in JUNK_PATTERNS) {
            cleaned = pattern.replace(cleaned, " ")
        }
        // Decompose unicode accents and remove non-ASCII / punctuation
        val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
        return normalized
            .replace(Regex("\\p{M}"), "") // remove diacritical marks
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(text: String): Set<String> {
        return text.split(" ")
            .map { it.trim() }
            .filter { it.length > 1 }
            .toSet()
    }
}
