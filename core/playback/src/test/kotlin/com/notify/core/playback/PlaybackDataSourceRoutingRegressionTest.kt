package com.notify.core.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression test verifying that the playback DataSource.Factory used by NotiFyPlaybackService
 * properly routes content:// and file:// schemes to ContentDataSource / FileDataSource,
 * bypassing CacheDataSource/DefaultHttpDataSource, while preserving progressive caching for http/https.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PlaybackDataSourceRoutingRegressionTest {

    companion object {
        const val AUTHORITY = "com.notify.test.offline"
        var testAudioFile: File? = null
    }

    class TestAudioContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String = "audio/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            val file = testAudioFile ?: return null
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, file.length())
        }
    }

    private lateinit var testData: ByteArray
    private lateinit var contentUri: Uri

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        Media3StreamCache.resetForTests()
        Robolectric.setupContentProvider(TestAudioContentProvider::class.java, AUTHORITY)

        testData = "NotiFy offline encrypted/audio sample payload with 64 bytes of test audio!".toByteArray(Charsets.UTF_8)
        val file = File(context.cacheDir, "test_track.m4a").apply {
            writeBytes(testData)
        }
        testAudioFile = file
        contentUri = Uri.parse("content://$AUTHORITY/test_track.m4a")
    }

    @After
    fun tearDown() {
        testAudioFile?.delete()
        testAudioFile = null
        Media3StreamCache.resetForTests()
    }

    @Test
    fun testContentUri_opensAndReads_andNeverOpensHttpUpstream() {
        val context = RuntimeEnvironment.getApplication()

        var httpTransferStartCount = 0
        var httpBytesTransferred = 0L

        val httpTransferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                httpTransferStartCount++
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                httpBytesTransferred += bytesTransferred
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }

        // Use the EXACT factory installed on the service's ExoPlayer
        val routingFactory = Media3StreamCache.createPlaybackDataSourceFactory(
            context = context,
            networkTransferListener = httpTransferListener
        )
        val dataSource = routingFactory.createDataSource()

        // 1. Open and read from the beginning
        val fullDataSpec = DataSpec(contentUri)
        val openedLength = dataSource.open(fullDataSpec)
        assertEquals(testData.size.toLong(), openedLength)

        val buffer = ByteArray(testData.size)
        val bytesRead = dataSource.read(buffer, 0, buffer.size)
        dataSource.close()

        assertEquals(testData.size, bytesRead)
        assertArrayEquals(testData, buffer)

        // 2. Read again at a non-zero offset
        val offset = 16L
        val offsetDataSpec = DataSpec.Builder()
            .setUri(contentUri)
            .setPosition(offset)
            .build()

        val offsetDataSource = routingFactory.createDataSource()
        val offsetOpenedLength = offsetDataSource.open(offsetDataSpec)
        assertEquals((testData.size - offset.toInt()).toLong(), offsetOpenedLength)

        val offsetBuffer = ByteArray(testData.size - offset.toInt())
        val offsetBytesRead = offsetDataSource.read(offsetBuffer, 0, offsetBuffer.size)
        offsetDataSource.close()

        assertEquals(testData.size - offset.toInt(), offsetBytesRead)
        val expectedOffsetSlice = testData.copyOfRange(offset.toInt(), testData.size)
        assertArrayEquals(expectedOffsetSlice, offsetBuffer)

        // 3. Assert that the HTTP upstream was NEVER opened
        assertEquals("HTTP upstream must never be opened when reading content:// URI", 0, httpTransferStartCount)
        assertEquals("Zero HTTP bytes transferred for content:// URI", 0L, httpBytesTransferred)
    }

    @Test
    fun testHttpPlayback_routesThroughCacheDataSourceAndSimpleCache() {
        val context = RuntimeEnvironment.getApplication()
        val httpUri = Uri.parse("https://rr1---sn-audio.googlevideo.com/videoplayback?id=test_1")
        val fakePayload = "simulated-http-streaming-bytes-for-progressive-cache-test".toByteArray(Charsets.UTF_8)
        var upstreamOpenCount = 0

        val fakeUpstreamFactory = DataSource.Factory {
            object : DataSource {
                private var currentUri: Uri? = null
                private var position = 0

                override fun addTransferListener(transferListener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long {
                    upstreamOpenCount++
                    currentUri = dataSpec.uri
                    position = dataSpec.position.toInt()
                    return (fakePayload.size - position).toLong()
                }
                override fun read(targetBuffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= fakePayload.size) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, fakePayload.size - position)
                    System.arraycopy(fakePayload, position, targetBuffer, offset, count)
                    position += count
                    return count
                }
                override fun getUri(): Uri? = currentUri
                override fun close() {}
            }
        }

        val routingFactory = Media3StreamCache.createPlaybackDataSourceFactory(
            context = context,
            upstreamDataSourceFactory = fakeUpstreamFactory
        )

        val httpDataSpec = DataSpec.Builder()
            .setUri(httpUri)
            .setKey("youtube:test_video_http:140")
            .build()

        // 1. First read: Cache miss -> queries upstream and writes into SimpleCache
        val dataSource1 = routingFactory.createDataSource()
        val len1 = dataSource1.open(httpDataSpec)
        val buf1 = ByteArray(fakePayload.size)
        val read1 = dataSource1.read(buf1, 0, buf1.size)
        dataSource1.close()

        assertEquals(fakePayload.size, read1)
        assertArrayEquals(fakePayload, buf1)
        assertEquals("First HTTP read must query upstream exactly once", 1, upstreamOpenCount)

        // 2. Second read: Cache HIT -> served directly from SimpleCache without opening upstream
        val dataSource2 = routingFactory.createDataSource()
        val len2 = dataSource2.open(httpDataSpec)
        val buf2 = ByteArray(fakePayload.size)
        val read2 = dataSource2.read(buf2, 0, buf2.size)
        dataSource2.close()

        assertEquals(fakePayload.size, read2)
        assertArrayEquals(fakePayload, buf2)
        assertEquals("Second HTTP read must be served from stream cache without hitting upstream", 1, upstreamOpenCount)
    }

    @Test
    fun testUnroutedCacheDataSourceFactory_failsOnContentUri_confirmingRegressionRootCause() {
        val context = RuntimeEnvironment.getApplication()
        // The old factory directly installed on ExoPlayer before this hotfix
        val oldCacheFactory = Media3StreamCache.getCacheDataSourceFactory(context)
        val oldDataSource = oldCacheFactory.createDataSource()

        // Opening content URI directly through CacheDataSource -> DefaultHttpDataSource fails with unknown protocol
        assertThrows(Exception::class.java) {
            oldDataSource.open(DataSpec(contentUri))
        }
    }
}
