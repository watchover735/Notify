package com.notify.download.matcher

import android.content.Context
import android.net.Uri
import android.util.Log
import com.notify.download.db.YouTubePlaylistTrack
import com.notify.download.spike.YtDlpRuntime
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ExtractedYouTubePlaylist(
    val id: String,
    val title: String,
    val artworkUrl: String?,
    val tracks: List<YouTubePlaylistTrack>
)

object YouTubePlaylistExtractor {

    private const val TAG = "YouTubePlaylistExtractor"

    /**
     * Extracts the YouTube playlist ID from raw input string or URL.
     * Supports:
     * - Direct ID (starts with PL, OLAK5uy_, RD, UU, etc.)
     * - Standard URL: https://www.youtube.com/playlist?list=PL...
     * - Shortened / Music URL: https://music.youtube.com/playlist?list=PL...
     * - Watch URL with list param: https://www.youtube.com/watch?v=...&list=PL...
     */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Direct ID without URL formatting
        if ((trimmed.startsWith("PL") || trimmed.startsWith("OLAK5uy_") || trimmed.startsWith("RD") || trimmed.startsWith("UU")) &&
            !trimmed.contains("/") && !trimmed.contains("?") && !trimmed.contains(" ")
        ) {
            return trimmed
        }

        // Try Uri parsing first
        try {
            val uri = Uri.parse(trimmed)
            val listParam = uri.getQueryParameter("list")
            if (!listParam.isNullOrBlank()) {
                return listParam.trim()
            }
        } catch (_: Exception) { }

        // Fallback regex matching list=...
        val regex = Regex("""[?&]list=([a-zA-Z0-9_-]+)""")
        val match = regex.find(trimmed)
        return match?.groupValues?.get(1)?.trim()
    }

    /**
     * Executes flat extraction via yt-dlp on [Dispatchers.IO].
     * Fetches metadata only (id, title, uploader, duration, thumbnail) without resolving audio streams.
     */
    suspend fun extractPlaylist(
        context: Context,
        playlistId: String
    ): Result<ExtractedYouTubePlaylist> = withContext(Dispatchers.IO) {
        try {
            val initResult = YtDlpRuntime.ensureReady(context)
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.message ?: "yt-dlp runtime unavailable"
                Log.e(TAG, "Extraction aborted: $err")
                return@withContext Result.failure(IllegalStateException("YouTube engine not available: $err"))
            }

            val canonicalUrl = "https://www.youtube.com/playlist?list=$playlistId"
            Log.i(TAG, "Starting flat extraction for: $canonicalUrl")

            val request = YoutubeDLRequest(canonicalUrl).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-check-certificates")
                addOption("--ignore-errors")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val rawOutput = response.out?.trim() ?: ""

            val startIdx = rawOutput.indexOf('{')
            val endIdx = rawOutput.lastIndexOf('}')
            if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
                return@withContext Result.failure(IllegalStateException("No valid playlist data returned by YouTube"))
            }

            val jsonString = rawOutput.substring(startIdx, endIdx + 1)
            val json = JSONObject(jsonString)

            val playlistTitle = json.optString("title", "YouTube Playlist").ifBlank { "YouTube Playlist" }
            val playlistThumbnail = run {
                val single = json.optString("thumbnail").takeIf { it.isNotBlank() }
                if (single != null) return@run single
                val thumbs = json.optJSONArray("thumbnails")
                if (thumbs != null && thumbs.length() > 0) {
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
                } else null
            }

            val entries = json.optJSONArray("entries")
            if (entries == null || entries.length() == 0) {
                return@withContext Result.failure(IllegalStateException("Playlist is empty or contains no playable videos."))
            }

            val tracks = mutableListOf<YouTubePlaylistTrack>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val videoId = entry.optString("id").ifBlank {
                    val url = entry.optString("url")
                    if (url.contains("v=")) url.substringAfter("v=").substringBefore("&") else ""
                }
                if (videoId.isBlank()) continue

                val title = entry.optString("title", "Unknown Title").ifBlank { "Unknown Title" }
                val artist = entry.optString("uploader", entry.optString("channel", "YouTube")).ifBlank { "YouTube" }
                val durationSec = entry.optDouble("duration", 0.0)
                val durationMs = if (durationSec > 0) (durationSec * 1000).toLong() else 0L

                val artworkUrl = run {
                    val t = entry.optString("thumbnail").takeIf { it.isNotBlank() }
                    if (t != null) return@run t
                    val thumbs = entry.optJSONArray("thumbnails")
                    if (thumbs != null && thumbs.length() > 0) {
                        thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
                    } else {
                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }
                }

                tracks.add(
                    YouTubePlaylistTrack(
                        videoId = videoId,
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        artworkUrl = artworkUrl,
                        position = tracks.size + 1
                    )
                )
            }

            if (tracks.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Playlist contains no accessible tracks."))
            }

            Log.i(TAG, "Extracted ${tracks.size} tracks from YouTube playlist '$playlistTitle'")
            Result.success(
                ExtractedYouTubePlaylist(
                    id = playlistId,
                    title = playlistTitle,
                    artworkUrl = playlistThumbnail,
                    tracks = tracks
                )
            )
        } catch (e: YoutubeDLException) {
            val msg = e.message.orEmpty()
            val friendlyMsg = when {
                msg.contains("Private playlist", ignoreCase = true) ->
                    "This playlist is private. Please make it public or unlisted."
                msg.contains("does not exist", ignoreCase = true) || msg.contains("404", ignoreCase = true) ->
                    "Playlist not found. Please verify the URL."
                msg.contains("network", ignoreCase = true) || msg.contains("Unable to download", ignoreCase = true) || msg.contains("offline", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
                    "Network error. Please check your internet connection."
                else -> "YouTube import failed: ${e.message?.take(120)}"
            }
            Log.e(TAG, "YouTube import error: $friendlyMsg", e)
            Result.failure(IllegalStateException(friendlyMsg, e))
        } catch (e: Exception) {
            Log.e(TAG, "YouTube import unexpected error", e)
            Result.failure(IllegalStateException("Failed to import playlist: ${e.message?.take(120)}", e))
        }
    }
}
