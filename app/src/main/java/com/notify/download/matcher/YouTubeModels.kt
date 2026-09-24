package com.notify.download.matcher

data class YouTubeCandidate(
    val videoId: String,
    val title: String,
    val channelTitle: String?,
    val durationMs: Long,
    val viewCount: Long? = null,
    val artworkUrl: String? = null,
    val album: String? = null,
    val provider: String = "youtube"
) {
    val canonicalWatchUrl: String
        get() = "https://www.youtube.com/watch?v=$videoId"
}

data class MatchResult(
    val candidate: YouTubeCandidate,
    val canonicalDownloadUrl: String,
    val matchScore: Float,
    val durationDeltaMs: Long,
    val isConfident: Boolean
)
