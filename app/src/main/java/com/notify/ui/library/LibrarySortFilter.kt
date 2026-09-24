package com.notify.ui.library

import com.notify.core.local.LocalAudioItem

enum class TrackSortOption(val displayName: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
    DATE_MODIFIED("Date Added")
}

enum class SortDirection {
    ASCENDING,
    DESCENDING
}

object LibrarySortFilter {
    /**
     * Filters by query and sorts by the given sort option and direction.
     */
    fun sortAndFilter(
        items: List<LocalAudioItem>,
        query: String = "",
        sortOption: TrackSortOption = TrackSortOption.TITLE,
        sortDirection: SortDirection = SortDirection.ASCENDING
    ): List<LocalAudioItem> {
        val filtered = if (query.isBlank()) {
            items
        } else {
            val q = query.trim()
            items.filter { item ->
                item.track.title.contains(q, ignoreCase = true) ||
                    item.track.artist.contains(q, ignoreCase = true) ||
                    (item.track.album?.contains(q, ignoreCase = true) == true)
            }
        }

        val sorted = when (sortOption) {
            TrackSortOption.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.track.title })
            TrackSortOption.ARTIST -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.track.artist })
            TrackSortOption.ALBUM -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.track.album ?: "" })
            TrackSortOption.DURATION -> filtered.sortedBy { it.track.durationMs }
            TrackSortOption.DATE_MODIFIED -> filtered.sortedBy { it.dateModifiedEpochSeconds }
        }

        return if (sortDirection == SortDirection.DESCENDING) {
            sorted.reversed()
        } else {
            sorted
        }
    }
}
