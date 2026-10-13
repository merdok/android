package io.panelassistant.android.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class EntityTelemetryWriteBarrierTest {
    @Test fun telemetryQueuedBeforeResetCannotWriteAfterInvalidation() {
        val generation = AtomicLong(3)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val admittedGeneration = generation.get()
        var wrote = false

        barrier.invalidateAndWrite(invalidate = { generation.incrementAndGet() }) {}

        assertFalse(barrier.writeIfCurrent(admittedGeneration) { wrote = true })
        assertFalse(wrote)
    }

    @Test fun resetWaitsForAnActiveTelemetryWriteThenClearsIt() {
        val generation = AtomicLong(8)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val evidence = mutableListOf<String>()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)

        val writer = thread(start = true) {
            barrier.writeIfCurrent(generation.get()) {
                writeEntered.countDown()
                releaseWrite.await()
                evidence += "old"
            }
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val resetter = thread(start = true) {
            resetAttempted.countDown()
            barrier.invalidateAndWrite(
                invalidate = { generation.incrementAndGet() },
                write = { evidence.clear() },
            )
            resetFinished.countDown()
        }

        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(resetFinished.await(50, TimeUnit.MILLISECONDS))
        releaseWrite.countDown()
        writer.join(2_000)
        resetter.join(2_000)

        assertFalse(writer.isAlive)
        assertFalse(resetter.isAlive)
        assertTrue(evidence.isEmpty())
    }

    @Test fun telemetryFollowUpReleasesItsBarrierBeforeEnteringTheManagerMonitor() {
        val generation = AtomicLong(5)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val managerMonitor = Any()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resetHasManagerMonitor = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val followUpFinished = CountDownLatch(1)

        val telemetry = thread(start = true, isDaemon = true) {
            writeEntityTelemetryThen(
                barrier = barrier,
                admittedGeneration = generation.get(),
                write = {
                    writeEntered.countDown()
                    releaseWrite.await()
                },
                afterWrite = {
                    synchronized(managerMonitor) { followUpFinished.countDown() }
                },
            )
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val reset = thread(start = true, isDaemon = true) {
            synchronized(managerMonitor) {
                resetHasManagerMonitor.countDown()
                barrier.invalidateAndWrite(invalidate = { generation.incrementAndGet() }) {}
            }
            resetFinished.countDown()
        }

        assertTrue(resetHasManagerMonitor.await(2, TimeUnit.SECONDS))
        releaseWrite.countDown()
        assertTrue("reset must acquire the released barrier instead of deadlocking",
            resetFinished.await(2, TimeUnit.SECONDS))
        assertTrue("telemetry follow-up must enter the manager only after reset releases it",
            followUpFinished.await(2, TimeUnit.SECONDS))
        telemetry.join(2_000)
        reset.join(2_000)
        assertFalse(telemetry.isAlive)
        assertFalse(reset.isAlive)
    }
}
