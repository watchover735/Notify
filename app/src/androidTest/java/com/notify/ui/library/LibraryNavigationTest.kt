package com.notify.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notify.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented runtime tests for the Library destination.
 *
 * Verifies:
 * 1. Launching MainActivity and navigating to Library does not crash with
 *    NoSuchMethodException (PlaylistLibraryViewModel Factory verification).
 * 2. Obsolete Device Music UI elements (Scan Audio, Import SAF, Device Audio Permission)
 *    are completely removed and never displayed.
 * 3. Library header, "New Playlist" button, and "Import" button are displayed.
 * 4. Create Playlist dialog and Import Playlist bottom sheet open without crashing.
 * 5. Repeated tab switches (Home -> Library -> Search -> Library) maintain stability.
 */
@RunWith(AndroidJUnit4::class)
class LibraryNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigateToLibrary_rendersRealPlaylistLibrary_andDoesNotCrash() {
        composeTestRule.waitForIdle()

        // Navigate to Library via bottom navigation bar
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()

        // Verify Library screen renders
        composeTestRule.onNodeWithTag("library_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_create_playlist").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_import_spotify").assertIsDisplayed()

        // Verify Device Music UI elements are completely absent
        assertEquals(
            "Scan Audio must NOT be displayed in Library",
            0,
            composeTestRule.onAllNodesWithText("Scan Audio").fetchSemanticsNodes().size
        )
        assertEquals(
            "Import SAF must NOT be displayed in Library",
            0,
            composeTestRule.onAllNodesWithText("Import SAF").fetchSemanticsNodes().size
        )
        assertEquals(
            "Device Audio Permission must NOT be displayed in Library",
            0,
            composeTestRule.onAllNodesWithText("Device Audio Permission").fetchSemanticsNodes().size
        )
        assertEquals(
            "No Local Audio Loaded must NOT be displayed in Library",
            0,
            composeTestRule.onAllNodesWithText("No Local Audio Loaded").fetchSemanticsNodes().size
        )

        // Verify Activity remains active
        assertFalse("Activity must remain active", composeTestRule.activity.isFinishing)
        assertFalse("Activity must not be destroyed", composeTestRule.activity.isDestroyed)
    }

    @Test
    fun openCreatePlaylistDialog_showsDialog_andDoesNotCrash() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_create_playlist").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("create_playlist_title_field").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertFalse("Activity must remain active after dialog interaction", composeTestRule.activity.isFinishing)
    }

    @Test
    fun openImportPlaylistSheet_showsBottomSheet_andDoesNotCrash() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_import_spotify").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("import_spotify_url_field").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertFalse("Activity must remain active after sheet dismissal", composeTestRule.activity.isFinishing)
    }

    @Test
    fun repeatedTabNavigation_preservesStability_andDoesNotCrash() {
        composeTestRule.waitForIdle()

        repeat(3) {
            composeTestRule.onNodeWithTag("nav_tab_library").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("library_title").assertIsDisplayed()

            composeTestRule.onNodeWithTag("nav_tab_search").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("nav_tab_home").performClick()
            composeTestRule.waitForIdle()
        }

        // Final check on Library
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_title").assertIsDisplayed()

        assertFalse("Activity must remain active across repeated tab switches", composeTestRule.activity.isFinishing)
    }
}
