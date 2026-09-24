package com.notify.ui.search

import com.notify.core.local.LocalAudioItem

object LocalSearchFilter {
    /**
     * Filters local tracks by title, artist, or album (case-insensitive).
     * Returns emptyList() if query is blank.
     */
    fun filter(items: List<LocalAudioItem>, query: String): List<LocalAudioItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return items.filter { item ->
            item.track.title.contains(trimmed, ignoreCase = true) ||
                item.track.artist.contains(trimmed, ignoreCase = true) ||
                (item.track.album?.contains(trimmed, ignoreCase = true) == true)
        }
    }
}
