package io.panelassistant.android.http

import io.panelassistant.android.testsupport.scratchDirectory

import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The stub's uploads stay within a bounded footprint on disk: in flight, evicted and expired. */
class StubBlobsTest {
    private val dir = scratchDirectory("ui-blobs-test").toFile()
    private val pool = Executors.newCachedThreadPool()

    /** A body that arrives only once [release] opens. */
    private class Held(private val release: CountDownLatch, private val started: CountDownLatch) : InputStream() {
        private var sent = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (sent) return -1
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            sent = true
            b[off] = 7
            return 1
        }
    }

    private fun token(minted: JSONObject?) = requireNotNull(minted).getString("url").substringAfterLast('/')

    @Test fun anUploadPastTheSlotCountIsRefusedAndAnEvictedUploadLeavesNoFile() {
        // A clock that moves on every reading, so the first upload is unambiguously the oldest.
        val clock = java.util.concurrent.atomic.AtomicLong()
        val blobs = StubBlobs(dir, nowMs = { clock.incrementAndGet() })
        val release = CountDownLatch(1)
        val tokens = (1..4).map { token(blobs.mintUpload("application/octet-stream")) }
        val started = CountDownLatch(4)
        val fills: List<Future<JSONObject?>> = tokens.map { t -> pool.submit<JSONObject?> { blobs.fill(t, 10, Held(release, started)) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // A fifth upload evicts the oldest while its body is still arriving.
        val fifth = token(blobs.mintUpload("application/octet-stream"))
        try {
            blobs.fill(fifth, 10, "x".byteInputStream())
            fail("a fill past the slot count is refused")
        } catch (_: StubBlobs.Busy) {
        }
        release.countDown()
        val answers = fills.map { it.get(5, TimeUnit.SECONDS) }
        assertNull("the evicted upload is not kept", answers[0])
        assertEquals("only the kept uploads remain on disk", 3, dir.list().orEmpty().size)
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    @Test fun anExpiredUploadIsDeletedWhenAnotherIsRead() {
        var now = 0L
        val blobs = StubBlobs(dir, ttlMs = 1_000, nowMs = { now })
        val stale = token(blobs.mintUpload("application/octet-stream"))
        blobs.fill(stale, 10, "stale".byteInputStream())
        now = 900
        val fresh = token(blobs.mintUpload("application/octet-stream"))
        val reference = requireNotNull(blobs.fill(fresh, 10, "fresh".byteInputStream()))
        assertEquals(2, dir.list().orEmpty().size)
        // The stale upload expires; no new upload is minted after it.
        now = 1_500
        blobs.take(reference)?.use { it.readBytes() }
        assertEquals("nothing is left once the fresh upload is read and the stale one has expired", 0, dir.list().orEmpty().size)
        dir.deleteRecursively()
    }
}
