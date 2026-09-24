package com.notify.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingHelperTest {

    @Test
    fun testMorningGreeting() {
        assertEquals("Good morning", GreetingHelper.getGreeting(5))
        assertEquals("Good morning", GreetingHelper.getGreeting(9))
        assertEquals("Good morning", GreetingHelper.getGreeting(11))
    }

    @Test
    fun testAfternoonGreeting() {
        assertEquals("Good afternoon", GreetingHelper.getGreeting(12))
        assertEquals("Good afternoon", GreetingHelper.getGreeting(14))
        assertEquals("Good afternoon", GreetingHelper.getGreeting(16))
    }

    @Test
    fun testEveningGreeting() {
        assertEquals("Good evening", GreetingHelper.getGreeting(17))
        assertEquals("Good evening", GreetingHelper.getGreeting(19))
        assertEquals("Good evening", GreetingHelper.getGreeting(20))
    }

    @Test
    fun testNightGreeting() {
        assertEquals("Good night", GreetingHelper.getGreeting(21))
        assertEquals("Good night", GreetingHelper.getGreeting(0))
        assertEquals("Good night", GreetingHelper.getGreeting(4))
    }
}
