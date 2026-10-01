package com.notify.download.stream

/**
 * Configuration constants for Supabase Edge Functions audio extraction backend.
 */
object SupabaseConfig {
    const val BASE_URL = "https://pnwoccbrpcihjhjmujfb.supabase.co/functions/v1"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBud29jY2JycGNpaGpoam11amZiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODk1NjEyNzAsImV4cCI6MjEwNTEzNzI3MH0.J0cVEmXbHG36R38ZpXDMxIQbw0v6tcSt5Qx1iEgGmXk"

    const val RESOLVE_JIOSAAVN_URL = "$BASE_URL/resolve-jiosaavn"
    const val RESOLVE_SOUNDCLOUD_URL = "$BASE_URL/resolve-soundcloud"
    const val RESOLVE_DEEZER_URL = "$BASE_URL/resolve-deezer"
    const val RESOLVE_COBALT_URL = "$BASE_URL/resolve-cobalt"
    const val TRENDING_JIOSAAVN_URL = "$BASE_URL/trending-jiosaavn"
    const val CURATED_ARTISTS_URL = "$BASE_URL/curated-artists"
}
