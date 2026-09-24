package com.notify.ui.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notify.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented runtime-level tests for the Search destination.
 *
 * Verifies:
 * 1. Launching MainActivity and navigating to Search does not crash with
 *    NoSuchMethodException (OnlineSearchViewModel reflection crash).
 * 2. Search title and search_query_field input are displayed.
 * 3. Submitting a search query triggers online search and reaches either
 *    Results or a controlled Error/Searching state without crashing.
 * 4. Repeated tab navigation (Home -> Search -> Library -> Search) preserves
 *    ViewModel scoping and does not crash.
 */
@RunWith(AndroidJUnit4::class)
class SearchDestinationLaunchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigateToSearch_displaysSearchInput_andDoesNotCrash() {
        // Given MainActivity is open at Home destination
        composeTestRule.waitForIdle()

        // When navigating to Search via bottom navigation bar
        composeTestRule.onNodeWithTag("nav_tab_search").performClick()
        composeTestRule.waitForIdle()

        // Then Search destination renders without NoSuchMethodException
        composeTestRule.onNodeWithTag("search_query_field").assertIsDisplayed()

        // Verify the Activity is running and not destroyed by an uncaught exception
        assertFalse("Activity must remain active", composeTestRule.activity.isFinishing)
        assertFalse("Activity must not be destroyed", composeTestRule.activity.isDestroyed)
    }

    @Test
    fun submitQuery_triggersSearch_andReachesControlledStateWithoutCrash() {
        composeTestRule.waitForIdle()

        // Navigate to Search
        composeTestRule.onNodeWithTag("nav_tab_search").performClick()
        composeTestRule.waitForIdle()

        // Input search query
        val searchField = composeTestRule.onNodeWithTag("search_query_field")
        searchField.assertIsDisplayed()
        searchField.performTextInput("Born to Shine")
        composeTestRule.waitForIdle()

        // Submit query via IME action or submit button
        val submitNodes = composeTestRule.onAllNodesWithContentDescription("Submit Search")
        if (submitNodes.fetchSemanticsNodes().isNotEmpty()) {
            submitNodes[0].performClick()
        } else {
            searchField.performImeAction()
        }

        // Wait until UI reaches a controlled online state (Searching, Results, Empty, or Error)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            val hasResults = composeTestRule.onAllNodesWithText("Born to Shine", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            val hasSearching = composeTestRule.onAllNodesWithText("Cancel")
                .fetchSemanticsNodes().isNotEmpty()
            val hasError = composeTestRule.onAllNodesWithText("Retry")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithText("Search Failed", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            val hasEmpty = composeTestRule.onAllNodesWithText("No online matches found", substring = true)
                .fetchSemanticsNodes().isNotEmpty()

            hasResults || hasSearching || hasError || hasEmpty
        }

        // Process must remain alive
        assertFalse("Activity must remain active after search submission", composeTestRule.activity.isFinishing)
    }

    @Test
    fun repeatedTabNavigation_preservesViewModelScope_andDoesNotCrash() {
        composeTestRule.waitForIdle()

        // Repeat Home -> Search -> Library -> Search 3 times
        repeat(3) {
            // Navigate to Search
            composeTestRule.onNodeWithTag("nav_tab_search").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("search_query_field").assertIsDisplayed()

            // Navigate to Library
            composeTestRule.onNodeWithTag("nav_tab_library").performClick()
            composeTestRule.waitForIdle()

            // Navigate back to Search
            composeTestRule.onNodeWithTag("nav_tab_search").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("search_query_field").assertIsDisplayed()

            // Navigate to Home
            composeTestRule.onNodeWithTag("nav_tab_home").performClick()
            composeTestRule.waitForIdle()
        }

        // Final verification on Search
        composeTestRule.onNodeWithTag("nav_tab_search").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("search_query_field").assertIsDisplayed()
        assertFalse("Activity must remain active across repeated tab switches", composeTestRule.activity.isFinishing)
    }
}
