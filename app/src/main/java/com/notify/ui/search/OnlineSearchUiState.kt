package com.notify.ui.search

import com.notify.download.matcher.YouTubeCandidate

/**
 * UI state for the online YouTube Music search panel inside SearchScreen.
 */
sealed interface OnlineSearchUiState {
    /** No query entered yet or query was cleared. */
    object Idle : OnlineSearchUiState

    /** Search request is actively executing with progress text. */
    data class Searching(
        val query: String = "",
        val progressText: String = "Searching YouTube Music…"
    ) : OnlineSearchUiState

    /** Backwards-compatible loading state for legacy views/tests. */
    data class Loading(
        val query: String = "",
        val isTakingLonger: Boolean = false
    ) : OnlineSearchUiState

    /** Fast primary search failed or timed out; trying compatibility fallback. */
    data class TryingFallback(
        val query: String = "",
        val reason: String = "Trying compatibility fallback…"
    ) : OnlineSearchUiState

    /** Search returned at least one candidate. */
    data class Results(
        val candidates: List<YouTubeCandidate>,
        val query: String = ""
    ) : OnlineSearchUiState

    /** Search completed with zero results across all providers. */
    data class Empty(val query: String) : OnlineSearchUiState

    /** Search error (runtime init, network error, or timeout). */
    data class Error(
        val message: String,
        val isNetworkError: Boolean = false,
        val retryableQuery: String? = null
    ) : OnlineSearchUiState

    /** Explicitly cancelled search. */
    object Cancelled : OnlineSearchUiState
}
