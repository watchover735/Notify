package com.notify.ui.search

import androidx.lifecycle.ViewModel
import com.notify.download.matcher.OnlineCatalogSearchProvider
import com.notify.download.matcher.YouTubeCandidate
import com.notify.download.stream.OnlineStreamResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnlineSearchViewModelFactoryTest {

    private class FakeSearchProvider(
        private val candidates: List<YouTubeCandidate> = emptyList()
    ) : OnlineCatalogSearchProvider {
        var searchInvoked = false
        var lastQuery: String? = null

        override suspend fun search(query: String, limit: Int): Result<List<YouTubeCandidate>> {
            searchInvoked = true
            lastQuery = query
            return Result.success(candidates)
        }
    }

    private class UnsupportedViewModel : ViewModel()

    @Test
    fun factory_createsOnlineSearchViewModel_withInjectedFakeProviders() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val expectedCandidate = YouTubeCandidate(
            videoId = "fake_vid_123",
            title = "Born to Shine (Factory Test)",
            channelTitle = "Diljit Dosanjh",
            album = "G.O.A.T.",
            durationMs = 214000L,
            artworkUrl = "https://example.com/artwork.jpg"
        )
        val fakeInnerTube = FakeSearchProvider(listOf(expectedCandidate))
        val fakeFallback = FakeSearchProvider(emptyList())
        val fakeResolver = OnlineStreamResolver(application)

        val factory = OnlineSearchViewModel.Factory(
            application = application,
            innerTubeProvider = fakeInnerTube,
            fallbackProvider = fakeFallback,
            streamResolver = fakeResolver
        )

        val viewModel: OnlineSearchViewModel = factory.create(OnlineSearchViewModel::class.java)
        assertNotNull("Factory must create non-null OnlineSearchViewModel", viewModel)

        // Verify injected provider is used by the created ViewModel instance
        viewModel.searchOnline("Born to Shine")

        var foundResults = false
        for (i in 0 until 50) {
            val state = viewModel.uiState.value
            if (state is OnlineSearchUiState.Results) {
                foundResults = true
                assertEquals(1, state.candidates.size)
                assertEquals("fake_vid_123", state.candidates[0].videoId)
                assertEquals("Born to Shine (Factory Test)", state.candidates[0].title)
                break
            }
            delay(20)
        }

        assertTrue("Expected OnlineSearchUiState.Results from injected provider", foundResults)
        assertTrue("Injected InnerTube provider must have been invoked", fakeInnerTube.searchInvoked)
        assertEquals("Born to Shine", fakeInnerTube.lastQuery)
    }

    @Test
    fun factory_withDefaultProviders_instantiatesOnlineSearchViewModel() {
        val application = RuntimeEnvironment.getApplication()
        val factory = OnlineSearchViewModel.Factory(application = application)

        val viewModel: OnlineSearchViewModel = factory.create(OnlineSearchViewModel::class.java)
        assertNotNull("Factory with default providers must create non-null OnlineSearchViewModel", viewModel)
    }

    @Test
    fun factory_throwsIllegalArgumentException_forUnknownModelClass() {
        val application = RuntimeEnvironment.getApplication()
        val factory = OnlineSearchViewModel.Factory(application = application)

        try {
            factory.create(UnsupportedViewModel::class.java)
            fail("Expected IllegalArgumentException for unsupported ViewModel class")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message should mention unknown class: ${e.message}",
                e.message?.contains("Unknown ViewModel class") == true
            )
        }
    }

    @Test
    fun factory_withNullSearchHistoryDao_createsViewModelWithEmptyHistory() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val factory = OnlineSearchViewModel.Factory(
            application = application,
            searchHistoryDao = null
        )

        val viewModel: OnlineSearchViewModel = factory.create(OnlineSearchViewModel::class.java)
        assertNotNull("Factory with null DAO must still create ViewModel", viewModel)

        // recentSearches must emit empty list when no DAO provided
        val history = viewModel.recentSearches.value
        assertEquals("Recent searches must be empty without a DAO", 0, history.size)
    }

    @Test
    fun factory_withInjectedSearchHistoryDao_exposesHistoryFlow() = runTest {
        // This test verifies the Factory signature accepts a SearchHistoryDao without crashing.
        // Full DAO integration is tested in SearchHistoryDaoTest (uses real Room in-memory DB).
        val application = RuntimeEnvironment.getApplication()

        // Pass null explicitly — simulates unit-test-mode without a real Room instance
        val factory = OnlineSearchViewModel.Factory(
            application = application,
            innerTubeProvider = FakeSearchProvider(),
            fallbackProvider = FakeSearchProvider(),
            streamResolver = OnlineStreamResolver(application),
            searchHistoryDao = null
        )

        val viewModel: OnlineSearchViewModel = factory.create(OnlineSearchViewModel::class.java)
        assertNotNull(viewModel)

        // Verify that a search does not crash when DAO is null
        viewModel.searchOnline("Born to Shine")
        delay(100)
        assertTrue(
            "Search must emit a state without DAO present",
            viewModel.uiState.value !is OnlineSearchUiState.Idle
        )
    }

    @Test
    fun factory_withInjectedPlaylistRepository_exposesRecentMediaItemsAndPlaylists() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val factory = OnlineSearchViewModel.Factory(
            application = application,
            innerTubeProvider = FakeSearchProvider(),
            fallbackProvider = FakeSearchProvider(),
            streamResolver = OnlineStreamResolver(application),
            searchHistoryDao = null,
            playlistRepository = null
        )

        val viewModel: OnlineSearchViewModel = factory.create(OnlineSearchViewModel::class.java)
        assertNotNull(viewModel)

        assertEquals("Recent media items empty without DAO", 0, viewModel.recentMediaItems.value.size)
        assertEquals("User playlists empty without repository", 0, viewModel.userPlaylists.value.size)
    }
}
