package com.notify.playback

import android.app.Application
import androidx.room.Room
import com.notify.core.model.AudioSource
import com.notify.core.model.CanonicalMediaKey
import com.notify.core.model.ProviderId
import com.notify.core.model.QueueEntry
import com.notify.core.model.QueueOrigin
import com.notify.core.model.ResolvedStream
import com.notify.core.model.Track
import com.notify.core.model.TrackId
import com.notify.download.db.NotiFyDatabase
import com.notify.download.stream.AudioStreamResolver
import com.notify.download.stream.ResolvedStreamProviderChain
import com.notify.download.stream.StreamUrlCache
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StreamResolutionAndPrefetchTest {

    private lateinit var app: Application
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var db: NotiFyDatabase

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        testScope = TestScope(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(app, NotiFyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NotiFyDatabase.setTestInstance(db)
        File(app.filesDir, "playback_snapshot.json").delete()
        StreamUrlCache.clear()
        ResolvedStreamProviderChain.clearInFlight()
    }

    @After
    fun tearDown() {
        StreamUrlCache.clear()
        ResolvedStreamProviderChain.clearInFlight()
        NotiFyDatabase.setTestInstance(null)
        db.close()
    }

    @Test
    fun streamUrlCache_storesAndRetrievesByCanonicalMediaKeyAndVideoId() {
        val stream = ResolvedStream(
            videoId = "dQw4w9WgXcQ",
            streamUrl = "https://example.com/audio.m4a",
            formatId = "140",
            expiresAtEpochMs = null // Should default to 3 hours
        )

        StreamUrlCache.put("youtube:dQw4w9WgXcQ", stream)

        // Retrieval by canonical key
        val byCanonical = StreamUrlCache.get("youtube:dQw4w9WgXcQ")
        assertNotNull("Must be retrievable by canonical key", byCanonical)
        assertEquals("dQw4w9WgXcQ", byCanonical?.videoId)
        assertTrue("TTL must be at least 2.9 hours", (byCanonical?.expiresAtEpochMs ?: 0L) > System.currentTimeMillis() + 2 * 3600_000L)

        // Retrieval by raw videoId
        val byRawId = StreamUrlCache.get("dQw4w9WgXcQ")
        assertNotNull("Must be retrievable by raw videoId", byRawId)
        assertEquals(byCanonical?.streamUrl, byRawId?.streamUrl)
    }

    @Test
    fun inFlightDeduplication_coalescesConcurrentResolutionsForSameVideoId() = runTest {
        val invocationCount = AtomicInteger(0)
        val slowResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                invocationCount.incrementAndGet()
                delay(50)
                return Result.success(
                    ResolvedStream(
                        videoId = "coalesce123",
                        streamUrl = "https://example.com/stream.m4a",
                        formatId = "140",
                        expiresAtEpochMs = System.currentTimeMillis() + 3600_000L
                    )
                )
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = app,
            fastPrimaryResolver = slowResolver,
            fallbackResolver = slowResolver,
            scope = this
        )

        // Fire 5 concurrent resolutions for the same videoId
        val jobs = (1..5).map {
            async {
                chain.resolveStream("https://www.youtube.com/watch?v=coalesce123")
            }
        }

        val results = jobs.awaitAll()

        // All 5 must succeed with the identical stream URL
        for (res in results) {
            assertTrue(res.isSuccess)
            assertEquals("https://example.com/stream.m4a", res.getOrThrow().streamUrl)
        }

        // Must have invoked the underlying resolver exactly ONCE due to in-flight deduplication
        assertEquals(1, invocationCount.get())
        assertFalse(ResolvedStreamProviderChain.isInFlight("coalesce123"))
    }

    @Test
    fun scopeResilience_callerCancellationDoesNotAbortApplicationResolution() = runTest {
        val completionSignal = CompletableDeferred<Boolean>()
        val slowResolver = object : AudioStreamResolver {
            override suspend fun resolveStream(canonicalYoutubeUrl: String): Result<ResolvedStream> {
                delay(50)
                completionSignal.complete(true)
                return Result.success(
                    ResolvedStream(
                        videoId = "resilient99",
                        streamUrl = "https://example.com/resilient.m4a",
                        formatId = "140",
                        expiresAtEpochMs = System.currentTimeMillis() + 3600_000L
                    )
                )
            }
        }

        val chain = ResolvedStreamProviderChain(
            context = app,
            fastPrimaryResolver = slowResolver,
            fallbackResolver = slowResolver
        )

        val callerJob = launch(Dispatchers.IO) {
            chain.resolveStream("https://www.youtube.com/watch?v=resilient99")
        }

        Thread.sleep(20)
        callerJob.cancel() // Cancel the caller

        val finished = completionSignal.await()
        assertTrue("Underlying resolution should complete despite caller cancellation", finished)

        var cached: ResolvedStream? = null
        for (i in 0 until 50) {
            cached = StreamUrlCache.get("resilient99")
            if (cached != null) break
            Thread.sleep(10)
        }
        assertNotNull("Result must be cached in StreamUrlCache", cached)
        assertEquals("https://example.com/resilient.m4a", cached?.streamUrl)
    }

    @Test
    fun playbackQueueCoordinator_trackEnded_usesPreResolvedCachedStreamImmediately() = runTest {
        val coordinator = PlaybackQueueCoordinator(app, testDispatcher)

        val track1 = Track(
            id = TrackId(ProviderId.YOUTUBE, "vid11111111"),
            title = "Track 1",
            artist = "Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid11111111")
        )
        val track2 = Track(
            id = TrackId(ProviderId.YOUTUBE, "vid22222222"),
            title = "Track 2",
            artist = "Artist",
            source = AudioSource.Remote(ProviderId.YOUTUBE, "vid22222222")
        )

        val entry1 = QueueEntry("q1", track1, QueueOrigin.PLAYLIST)
        val entry2 = QueueEntry("q2", track2, QueueOrigin.PLAYLIST)

        // Pre-cache track 2 in StreamUrlCache
        val preResolvedStream = ResolvedStream(
            videoId = "vid22222222",
            streamUrl = "https://example.com/track2.m4a",
            formatId = "140",
            expiresAtEpochMs = System.currentTimeMillis() + 3600_000L
        )
        StreamUrlCache.put("youtube:vid22222222", preResolvedStream)

        coordinator.playQueue(listOf(entry1, entry2), startIndex = 0, playImmediately = false)

        // Verify track 2 can be retrieved directly by CanonicalMediaKey
        val key2 = CanonicalMediaKey.fromTrack(track2)
        val cached = StreamUrlCache.get(key2)
        assertNotNull(cached)
        assertEquals("https://example.com/track2.m4a", cached?.streamUrl)
    }
}
