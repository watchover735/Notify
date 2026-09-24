package com.notify.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDestinationTest {

    @Test
    fun testBottomNavTabsContainsThreeScreens() {
        assertEquals(3, NotiFyDestination.bottomNavTabs.size)
        assertEquals(NotiFyDestination.Home, NotiFyDestination.bottomNavTabs[0])
        assertEquals(NotiFyDestination.Search, NotiFyDestination.bottomNavTabs[1])
        assertEquals(NotiFyDestination.Library, NotiFyDestination.bottomNavTabs[2])
    }

    @Test
    fun testDestinationRoutesAreUnique() {
        val routes = listOf(
            NotiFyDestination.Home.route,
            NotiFyDestination.Search.route,
            NotiFyDestination.Library.route,
            NotiFyDestination.NowPlaying.route
        )
        assertEquals(routes.distinct().size, routes.size)
    }

    @Test
    fun testNowPlayingIsNotInBottomNavTabs() {
        assertTrue(NotiFyDestination.bottomNavTabs.none { it.route == NotiFyDestination.NowPlaying.route })
    }
}
