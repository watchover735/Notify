package com.notify.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalMediaKeyTest {

    @Test
    fun fromResolved_normalizesProviderAndSourceId() {
        assertEquals(
            "youtube:abc123xyz",
            CanonicalMediaKey.fromResolved("YOUTUBE", "  abc123xyz  ")
        )
        assertEquals(
            "youtube:abc123xyz",
            CanonicalMediaKey.fromResolved("YouTube", "abc123xyz")
        )
        assertEquals(
            "local:content://media/1",
            CanonicalMediaKey.fromResolved("LOCAL", "content://media/1")
        )
    }

    @Test
    fun fromTrack_prefersResolvedRemoteSource() {
        val track = Track(
            id = TrackId(ProviderId.SPOTIFY, "spotify_catalog_id"),
            title = "Jailer",
            artist = "Anirudh Ravichander",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "youtube_resolved_id")
        )

        val key = CanonicalMediaKey.fromTrack(track)
        assertEquals("youtube:youtube_resolved_id", key)
    }

    @Test
    fun fromTrack_handlesLocalAndOffline() {
        val localTrack = Track(
            id = TrackId(ProviderId.LOCAL, "content://media/1"),
            title = "Local Song",
            artist = "Artist",
            source = AudioSource.Local("content://media/1")
        )
        assertEquals("local:content://media/1", CanonicalMediaKey.fromTrack(localTrack))

        val offlineTrack = Track(
            id = TrackId(ProviderId.YOUTUBE, "yt_123"),
            title = "Offline Song",
            artist = "Artist",
            source = AudioSource.Offline("relative/path/123", DownloadBucket.PINNED)
        )
        assertEquals("offline:relative/path/123", CanonicalMediaKey.fromTrack(offlineTrack))
    }

    @Test
    fun fingerprint_normalizesCorrectly() {
        val fp1 = CanonicalMediaKey.fingerprint("Jailer - Theme", "Anirudh!")
        val fp2 = CanonicalMediaKey.fingerprint("jailertheme", "anirudh")
        assertEquals(fp1, fp2)
    }
}
