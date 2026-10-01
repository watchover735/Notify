package com.notify.download.stream

/**
 * Configuration entry for a curated artist featured on the Home screen.
 *
 * @property name     Display name of the artist
 * @property artistId Optional JioSaavn internal artist ID for direct fast lookup
 */
data class CuratedArtistConfig(
    val name: String,
    val artistId: String? = null
)

/**
 * Master configuration for popular artist sections on the Home screen.
 *
 * Adding, removing, or re-ordering artists here automatically updates the Home screen sections.
 */
object CuratedArtistsConfig {
    val ARTISTS: List<CuratedArtistConfig> = listOf(
        CuratedArtistConfig(name = "Yo Yo Honey Singh", artistId = "485956"),
        CuratedArtistConfig(name = "Diljit Dosanjh", artistId = "468245"),
        CuratedArtistConfig(name = "Arijit Singh", artistId = "459320"),
        CuratedArtistConfig(name = "Karan Aujla", artistId = "697691"),
        CuratedArtistConfig(name = "AP Dhillon", artistId = "681966"),
        CuratedArtistConfig(name = "Badshah", artistId = "456863"),
        CuratedArtistConfig(name = "Dhanda Nyoliwala", artistId = "8812560")
    )
}
