package com.notify.download.spike

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notify.download.db.DownloadState
import com.notify.download.db.PlaylistEntryWithTrack
import com.notify.download.db.ResolutionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose test for SavedTrackEntryRow click behavior.
 * Verifies that tapping either the row or the stream button invokes the onStream callback
 * exactly once, preventing double-invocation or lost-click regressions.
 */
@RunWith(AndroidJUnit4::class)
class SpikeDialogClickTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingSavedTrackRow_invokesCallbackExactlyOnce() {
        var clickCount = 0
        val dummyEntry = PlaylistEntryWithTrack(
            entryId = 1L,
            playlistId = "test_playlist",
            position = 1,
            trackId = "spotify_track_123",
            title = "Born to Shine",
            artist = "Diljit Dosanjh",
            album = "G.O.A.T.",
            durationMs = 214000L,
            artworkUri = null,
            spotifyId = "spotify_track_123",
            resolutionState = ResolutionState.METADATA_ONLY,
            downloadState = DownloadState.NOT_DOWNLOADED,
            localContentUri = null,
            dateAddedEpochMs = 1000L
        )

        composeTestRule.setContent {
            SavedTrackEntryRow(
                entry = dummyEntry,
                isResolving = false,
                onStream = { clickCount++ }
            )
        }

        // Tap the entire row
        composeTestRule.onNodeWithTag("saved_track_row_spotify_track_123").performClick()

        // Verify exactly one callback invocation
        assertEquals("Clicking the saved track row must invoke the onStream callback exactly once", 1, clickCount)
    }

    @Test
    fun tappingStreamButtonArea_invokesCallbackExactlyOnce() {
        var clickCount = 0
        val dummyEntry = PlaylistEntryWithTrack(
            entryId = 2L,
            playlistId = "test_playlist",
            position = 2,
            trackId = "spotify_track_456",
            title = "Lover",
            artist = "Diljit Dosanjh",
            album = "MoonChild Era",
            durationMs = 190000L,
            artworkUri = null,
            spotifyId = "spotify_track_456",
            resolutionState = ResolutionState.METADATA_ONLY,
            downloadState = DownloadState.NOT_DOWNLOADED,
            localContentUri = null,
            dateAddedEpochMs = 2000L
        )

        composeTestRule.setContent {
            SavedTrackEntryRow(
                entry = dummyEntry,
                isResolving = false,
                onStream = { clickCount++ }
            )
        }

        // Tap the stream button area
        composeTestRule.onNodeWithTag("stream_button_spotify_track_456", useUnmergedTree = true).performClick()

        // Verify exactly one callback invocation
        assertEquals("Clicking the stream button must invoke the onStream callback exactly once", 1, clickCount)
    }
}
