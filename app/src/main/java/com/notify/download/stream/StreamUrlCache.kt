package com.notify.download.stream

import com.notify.core.model.ResolvedStream
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory process-wide cache for complete resolved audio streams.
 *
 * Invariants:
 * 1. Stores complete [ResolvedStream] (formatId, bitrate, container, headers, expiration),
 *    not merely a String URL.
 * 2. Applies conservative expiration margin (60s).
 * 3. Supports invalidation on HTTP 403 and limits retries to exactly 1 per videoId to prevent infinite loops.
 * 4. Ephemeral in-memory only: signed stream URLs are NEVER written to Room.
 */
object StreamUrlCache {

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val retry403Attempts = ConcurrentHashMap<String, Int>()
    const val DEFAULT_TTL_MS = 3 * 3600_000L // 3 hours

    /**
     * Retrieves an unexpired [ResolvedStream] for [key] (either a videoId or canonicalMediaKey),
     * or null if missing or expired.
     */
    fun get(key: String): ResolvedStream? {
        val trimmed = key.trim()
        val candidateKeys = buildKeyVariants(trimmed)
        for (k in candidateKeys) {
            val stream = cache[k] ?: continue
            if (stream.isExpired(safetyMarginMs = 60_000L)) {
                invalidate(k)
                continue
            }
            return stream
        }
        return null
    }

    /**
     * Stores a resolved stream in cache keyed by its [ResolvedStream.videoId] and canonicalMediaKey variants.
     */
    fun put(stream: ResolvedStream) {
        val vId = stream.videoId
        if (!vId.isNullOrBlank()) {
            put(vId, stream)
        }
    }

    /**
     * Stores a resolved stream in cache explicitly keyed by [key] as well as its [ResolvedStream.videoId] variants.
     * Enforces at least a 3-hour TTL if expiresAtEpochMs is not explicitly set.
     */
    fun put(key: String, stream: ResolvedStream) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        val effectiveExpiresAt = stream.expiresAtEpochMs
            ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)
        val streamToCache = if (stream.expiresAtEpochMs == null) {
            stream.copy(expiresAtEpochMs = effectiveExpiresAt)
        } else {
            stream
        }

        val keysToStore = buildKeyVariants(trimmed).toMutableSet()
        val vId = streamToCache.videoId
        if (!vId.isNullOrBlank()) {
            keysToStore.addAll(buildKeyVariants(vId))
            retry403Attempts.remove(vId)
        }

        for (k in keysToStore) {
            cache[k] = streamToCache
        }
    }

    /**
     * Checks if an unexpired stream is available for [key].
     */
    fun containsKey(key: String): Boolean {
        return get(key) != null
    }

    private fun buildKeyVariants(key: String): List<String> {
        val variants = mutableListOf(key)
        if (key.startsWith("youtube:")) {
            variants.add(key.removePrefix("youtube:"))
        } else if (!key.contains(":")) {
            variants.add("youtube:$key")
        }
        return variants
    }

    /**
     * Invalidates a cached stream (e.g. upon encountering an HTTP 403 Forbidden).
     */
    fun invalidate(key: String) {
        val variants = buildKeyVariants(key.trim())
        for (k in variants) {
            cache.remove(k)
        }
    }

    /**
     * Checks whether an HTTP 403 retry is permitted for [videoId].
     * Enforces exactly 1 retry attempt to avoid infinite loops.
     */
    fun canRetry403(videoId: String): Boolean {
        val rawId = videoId.removePrefix("youtube:")
        val attempts = retry403Attempts.getOrDefault(rawId, 0)
        return if (attempts < 1) {
            retry403Attempts[rawId] = attempts + 1
            invalidate(rawId)
            true
        } else {
            false
        }
    }

    /**
     * Resets retry tracking for [videoId].
     */
    fun resetRetry403(videoId: String) {
        val rawId = videoId.removePrefix("youtube:")
        retry403Attempts.remove(rawId)
    }

    /**
     * Clears all cached stream entries.
     */
    fun clear() {
        cache.clear()
        retry403Attempts.clear()
    }
}
