package com.notify.core.downloads.storage

/**
 * Audio quality profiles for download format selection.
 * Controls yt-dlp format string and target bitrate.
 *
 * Default: NORMAL (~128 kbps M4A/AAC or WebM/Opus).
 * INVARIANT: Never transcode lossy audio to a higher bitrate (e.g. 320 kbps MP3).
 */
enum class AudioQualityProfile(
    val displayName: String,
    val ytDlpFormatString: String,
    val maxBitrateKbps: Int,
    val preferredExtensions: List<String>
) {
    /**
     * ~64 kbps: conserve storage and bandwidth.
     */
    DATA_SAVER(
        displayName = "Data Saver",
        ytDlpFormatString = "bestaudio[abr<=96]/bestaudio",
        maxBitrateKbps = 96,
        preferredExtensions = listOf("m4a", "webm", "opus")
    ),

    /**
     * ~128 kbps: default quality, good balance of size and fidelity.
     */
    NORMAL(
        displayName = "Normal",
        ytDlpFormatString = "bestaudio[abr<=160]/bestaudio",
        maxBitrateKbps = 160,
        preferredExtensions = listOf("m4a", "webm", "opus")
    ),

    /**
     * Best available: up to ~256 kbps, still no transcoding.
     */
    HIGH(
        displayName = "High",
        ytDlpFormatString = "bestaudio",
        maxBitrateKbps = 320,
        preferredExtensions = listOf("m4a", "webm", "opus", "mp3")
    );

    /**
     * Returns the yt-dlp `-f` option value for this profile.
     */
    fun toYtDlpOption(): String = ytDlpFormatString
}
