package com.notify.download.matcher

/**
 * Common abstraction for online catalog and track search providers.
 */
interface OnlineCatalogSearchProvider {
    /**
     * Searches the online catalog for candidates matching the query.
     */
    suspend fun search(query: String, limit: Int = 5): Result<List<YouTubeCandidate>>
}
