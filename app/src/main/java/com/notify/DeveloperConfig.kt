package com.notify

/**
 * Developer contact and credit configuration.
 * Single source of truth for developer handles and contact endpoints.
 *
 * NOTE: WHATSAPP_NUMBER must contain digits only with country code (e.g. "91XXXXXXXXXX").
 * Do not include '+' or spaces or dashes.
 */
object DeveloperConfig {
    const val DEVELOPED_BY = "Developed by Rahul"

    /** Full Instagram profile URL — used for browser fallback. */
    const val INSTAGRAM_URL = "https://www.instagram.com/rahul__agarwal_001/"
    /** Handle extracted from INSTAGRAM_URL — used for app deep-link and subtitle display. */
    const val INSTAGRAM_HANDLE = "rahul__agarwal_001"

    /** Digits only, including country code. */
    const val WHATSAPP_NUMBER = "918168711383"

    const val WHATSAPP_PREFILLED_MESSAGE = "Hello Rahul, I would like to talk about NotiFy."
}
