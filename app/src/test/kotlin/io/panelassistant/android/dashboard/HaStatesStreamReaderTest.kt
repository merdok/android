package io.panelassistant.android.dashboard

import io.panelassistant.android.util.ByteLimitExceeded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class HaStatesStreamReaderTest {
    @Test fun stateProjectionBoundsFriendlyName() {
        val limits = HaStatesReadLimits(maxFriendlyNameChars = 18)
        assertEquals(
            EntityCatalogStore.StateRow("binary_sensor.window", "on", "Upper Window Group"),
            validateHaStateRow("binary_sensor.window", "on", 1, limits, "Upper Window Group"),
        )
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.room", "on", 1, limits, "A friendly name that is too long")
        }
    }

    @Test fun stateResponseBoundsAcceptProjectionAndRejectOversizePayloadRowsAndFields() {
        val limits = HaStatesReadLimits(maxBytes = 8, maxRows = 1, maxEntityIdChars = 16, maxStateChars = 4)
        assertEquals(
            EntityCatalogStore.StateRow("sensor.room", "21.5"),
            validateHaStateRow("sensor.room", "21.5", 1, limits),
        )
        assertThrows(ByteLimitExceeded::class.java) {
            DeadlineBoundedInputStream(ByteArrayInputStream(ByteArray(9)), 8, 1_000) { 0 }.readBytes()
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.two", "off", 2, limits)
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.identifier_too_long", "on", 1, limits)
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.room", "state-too-long", 1, limits)
        }
    }

    @Test fun stateResponseStreamEnforcesMonotonicReadDeadline() {
        var now = 0L
        assertThrows(SocketTimeoutException::class.java) {
            DeadlineBoundedInputStream(
                ByteArrayInputStream(byteArrayOf(1)), 16, 1,
                nanoTime = { (now++ * 2_000_000L) },
            ).read()
        }
    }

    @Test fun stateResponseStreamHonorsThreadInterruption() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                DeadlineBoundedInputStream(ByteArrayInputStream(byteArrayOf(1)), 16, 1_000) { 0 }.read()
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun stateResponseByteCountingDoesNotRetainAnotherPayloadCopy() {
        val payload = """[{"entity_id":"sensor.room","state":"21","attributes":{}}]""".toByteArray()
        val counted = CountingInputStream(ByteArrayInputStream(payload))
        assertEquals(1, readHaStates(counted).size)
        assertEquals(payload.size.toLong(), counted.bytesRead)
    }

    @Test fun streamingStateReaderHandlesEscapesNumbersAndNestedSkippedValues() {
        val payload = """[{"ignored":{"nested":[1,true,null,{"value":"discard"}]},"attributes":{"unit":"C","friendly_name":"Room \u2603"},"state":21.5,"entity_id":"sensor.room"}]"""

        assertEquals(
            listOf(EntityCatalogStore.StateRow("sensor.room", "21.5", "Room ☃")),
            readHaStates(ByteArrayInputStream(payload.toByteArray())),
        )
    }

    @Test fun streamingStateReaderRejectsOversizeFieldsAndTrailingDocuments() {
        val limits = HaStatesReadLimits(maxEntityIdChars = 8)
        assertThrows(IllegalStateException::class.java) {
            readHaStates(ByteArrayInputStream("""[{"entity_id":"sensor.identifier_too_long","state":"on"}]""".toByteArray()), limits)
        }
        assertThrows(IllegalStateException::class.java) {
            readHaStates(ByteArrayInputStream("[]{}".toByteArray()))
        }
    }
}
