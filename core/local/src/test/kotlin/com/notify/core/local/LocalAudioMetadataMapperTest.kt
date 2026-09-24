package com.notify.core.local

import com.notify.core.model.AudioSource
import com.notify.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioMetadataMapperTest {

    @Test
    fun testFromRowMapsFieldsCorrectly() {
        val row = MediaStoreAudioRow(
            id = 42L,
            title = "Clair de Lune",
            artist = "Claude Debussy",
            album = "Suite bergamasque",
            durationMs = 300_000L,
            mimeType = "audio/mp3",
            sizeBytes = 6_000_000L,
            dateModifiedEpochSeconds = 1710000000L
        )

        val item = LocalAudioMetadataMapper.fromRow(
            row = row,
            itemContentUriString = "content://media/external/audio/media/42"
        )

        assertEquals("content://media/external/audio/media/42", item.track.id.rawId)
        assertEquals(ProviderId.LOCAL, item.track.id.provider)
        assertEquals("Clair de Lune", item.track.title)
        assertEquals("Claude Debussy", item.track.artist)
        assertEquals("Suite bergamasque", item.track.album)
        assertEquals(300_000L, item.track.durationMs)
        assertNull(item.track.artworkUri) // Phase 2 invariant: no legacy artwork URI
        assertTrue(item.track.source is AudioSource.Local)
        assertEquals("content://media/external/audio/media/42", (item.track.source as AudioSource.Local).contentUriString)
        assertEquals(6_000_000L, item.sizeBytes)
        assertEquals("audio/mp3", item.mimeType)
        assertEquals(1710000000L, item.dateModifiedEpochSeconds)
    }

    @Test
    fun testFromRowAppliesSafeFallbacksForNullOrEmptyMetadata() {
        val row = MediaStoreAudioRow(
            id = 99L,
            title = null,
            artist = "   ",
            album = "",
            durationMs = null,
            mimeType = null,
            sizeBytes = null,
            dateModifiedEpochSeconds = null
        )

        val item = LocalAudioMetadataMapper.fromRow(
            row = row,
            itemContentUriString = "content://media/external/audio/media/99"
        )

        assertEquals("Unknown Title", item.track.title)
        assertEquals("Unknown Artist", item.track.artist)
        assertEquals("Unknown Album", item.track.album)
        assertEquals(0L, item.track.durationMs)
        assertEquals(0L, item.sizeBytes)
        assertNull(item.mimeType)
        assertEquals(0L, item.dateModifiedEpochSeconds)
        assertEquals("content://media/external/audio/media/99", item.track.id.rawId)
    }

    @Test
    fun testFromRowClampsNegativeDurationToZero() {
        val row = MediaStoreAudioRow(
            id = 10L,
            title = "Test",
            artist = "Artist",
            album = "Album",
            durationMs = -5000L,
            mimeType = "audio/mpeg",
            sizeBytes = 100L,
            dateModifiedEpochSeconds = 100L
        )

        val item = LocalAudioMetadataMapper.fromRow(
            row = row,
            itemContentUriString = "content://media/external/audio/media/10"
        )

        assertEquals(0L, item.track.durationMs)
    }

    @Test
    fun testMediaStoreItemUriRegressionId1000049464() {
        val row = MediaStoreAudioRow(
            id = 1000049464L,
            title = "Regression Track",
            artist = "Artist",
            album = "Album",
            durationMs = 210_000L,
            mimeType = "audio/mp3",
            sizeBytes = 5_000_000L,
            dateModifiedEpochSeconds = 1710000000L
        )

        val expectedUri = "content://media/external/audio/media/1000049464"
        val forbiddenDoubleUri = "content://media/external/audio/media/1000049464/1000049464"

        val item = LocalAudioMetadataMapper.fromRow(
            row = row,
            itemContentUriString = expectedUri
        )

        // Verifies mapper creates the MediaStore item URI exactly once
        assertEquals(expectedUri, item.track.id.rawId)
        assertFalse(item.track.id.rawId == forbiddenDoubleUri)

        // Verifies AudioSource.Local contains the exact expected URI
        val source = item.track.source as AudioSource.Local
        assertEquals(expectedUri, source.contentUriString)
        assertFalse(source.contentUriString == forbiddenDoubleUri)

        // Verifies URI path does not end with the same ID repeated twice
        assertFalse(source.contentUriString.endsWith("1000049464/1000049464"))
    }

    @Test
    fun testFromSafDocumentStripsFileExtension() {
        val item = LocalAudioMetadataMapper.fromSafDocument(
            contentUriString = "content://com.android.providers.downloads.documents/document/raw%3A123",
            displayName = "Symphony_No_5.mp3",
            sizeBytes = 8_500_000L,
            mimeType = "audio/mp3",
            durationMs = 450_000L,
            artist = "Beethoven",
            album = "Classic Masterworks"
        )

        assertEquals("Symphony_No_5", item.track.title)
        assertEquals("Beethoven", item.track.artist)
        assertEquals("Classic Masterworks", item.track.album)
        assertEquals(450_000L, item.track.durationMs)
        assertEquals(8_500_000L, item.sizeBytes)
        assertNull(item.track.artworkUri)
        assertTrue(item.track.source is AudioSource.Local)
    }

    @Test
    fun testFromSafDocumentFallbacksForNullName() {
        val item = LocalAudioMetadataMapper.fromSafDocument(
            contentUriString = "content://saf/doc/1",
            displayName = null,
            sizeBytes = null,
            mimeType = null
        )

        assertEquals("Unknown Title", item.track.title)
        assertEquals("Unknown Artist", item.track.artist)
        assertEquals("Unknown Album", item.track.album)
        assertEquals(0L, item.track.durationMs)
        assertEquals(0L, item.sizeBytes)
    }
}
