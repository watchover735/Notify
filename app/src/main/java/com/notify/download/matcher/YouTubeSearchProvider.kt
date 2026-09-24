package com.notify.download.matcher

interface YouTubeSearchProvider : OnlineCatalogSearchProvider {
    /**
     * Searches YouTube for candidate videos matching the query.
     */
    override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>>
}
