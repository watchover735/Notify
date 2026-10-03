package com.notify.download.stream

/**
 * Configuration constants for Supabase backend and audio extraction.
 */
object SupabaseConfig {
    const val PROJECT_URL = "https://pnwoccbrpcihjhjmujfb.supabase.co"
    const val AUTH_URL = "$PROJECT_URL/auth/v1"
    const val REST_URL = "$PROJECT_URL/rest/v1"

    // Placeholder for Google Cloud OAuth Web Client ID (from Google Cloud Console)
    const val GOOGLE_WEB_CLIENT_ID = "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"

    const val BASE_URL = "https://pnwoccbrpcihjhjmujfb.supabase.co/functions/v1"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBud29jY2JycGNpaGpoam11amZiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODk1NjEyNzAsImV4cCI6MjEwNTEzNzI3MH0.J0cVEmXbHG36R38ZpXDMxIQbw0v6tcSt5Qx1iEgGmXk"

    const val RESOLVE_JIOSAAVN_URL = "$BASE_URL/resolve-jiosaavn"
    const val RESOLVE_SOUNDCLOUD_URL = "$BASE_URL/resolve-soundcloud"
    const val RESOLVE_DEEZER_URL = "$BASE_URL/resolve-deezer"
    const val RESOLVE_COBALT_URL = "$BASE_URL/resolve-cobalt"
    const val TRENDING_JIOSAAVN_URL = "$BASE_URL/trending-jiosaavn"
    const val CURATED_ARTISTS_URL = "$BASE_URL/curated-artists"
}
