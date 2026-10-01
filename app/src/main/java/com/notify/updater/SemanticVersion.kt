package com.notify.updater

/**
 * Robust semantic version parser and comparator.
 *
 * Correctly compares versions with multi-digit segments (e.g. "v1.0.10" > "v1.0.9")
 * rather than doing lexicographical string comparisons.
 *
 * Tolerant to prefixes ("v", "V"), differing segment counts ("1.0" vs "1.0.0"),
 * and pre-release identifiers ("-beta", "-rc").
 */
data class SemanticVersion(
    val segments: List<Int>,
    val preRelease: String = ""
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val maxSegments = maxOf(segments.size, other.segments.size)
        for (i in 0 until maxSegments) {
            val segA = segments.getOrElse(i) { 0 }
            val segB = other.segments.getOrElse(i) { 0 }
            if (segA != segB) {
                return segA.compareTo(segB)
            }
        }

        // When numerical segments are identical:
        // A release without a pre-release suffix (e.g. 1.0.0) is higher than a pre-release (1.0.0-beta).
        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
        return preRelease.compareTo(other.preRelease)
    }

    companion object {
        /**
         * Parses a version string like "v1.0.10", "1.0.9", "v0.6.0" into a [SemanticVersion].
         * Returns null if parsing fails or input is malformed.
         */
        fun parse(versionStr: String?): SemanticVersion? {
            if (versionStr.isNullOrBlank()) return null
            val trimmed = versionStr.trim().removePrefix("v").removePrefix("V").trim()
            if (trimmed.isEmpty()) return null

            val separatorIdx = trimmed.indexOfAny(charArrayOf('-', '+'))
            val mainVersion = if (separatorIdx >= 0) trimmed.substring(0, separatorIdx) else trimmed
            val preRelease = if (separatorIdx >= 0) trimmed.substring(separatorIdx + 1).trim() else ""

            val segStrings = mainVersion.split('.')
            val segments = mutableListOf<Int>()
            for (seg in segStrings) {
                val num = seg.trim().filter { it.isDigit() }.toIntOrNull() ?: return null
                segments.add(num)
            }
            if (segments.isEmpty()) return null
            return SemanticVersion(segments, preRelease)
        }

        /**
         * Returns true if [latestVersion] is strictly newer than [currentVersion].
         * Fails silently (returns false) if either version string cannot be parsed.
         */
        fun isNewer(latestVersion: String?, currentVersion: String?): Boolean {
            val latest = parse(latestVersion) ?: return false
            val current = parse(currentVersion) ?: return false
            return latest > current
        }
    }
}
