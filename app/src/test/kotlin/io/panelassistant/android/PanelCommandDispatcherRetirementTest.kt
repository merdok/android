package io.panelassistant.android

import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.MonotonicDeadline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PanelCommandDispatcherRetirementTest : PanelRuntimeRig() {
    @Test fun idleDispatcherDrainsWhenInterruptionWinsTheCloseNotification() {
        val rig = rig()
        val worker = AtomicReference<Thread>()
        val answered = CountDownLatch(1)
        // Only scheduling uses the private monitor: force the lost-wakeup interleaving without a
        // production test hook. All retirement and admission assertions use the real caller APIs.
        val queue = PanelCommandDispatcher::class.java.getDeclaredField("queue").apply {
            isAccessible = true
        }.get(rig.commands) as CommandQueue
        val monitor = CommandQueue::class.java.getDeclaredField("lock").apply {
            isAccessible = true
        }.get(queue) as Object
        try {
            rig.commands.submit(PanelAssistantCommand("volume", "50", admit = {
                worker.set(Thread.currentThread())
                PanelAssistantCommandResult.Refused("scheduling-barrier")
            })) { result ->
                assertEquals(PanelAssistantCommandResult.Refused("scheduling-barrier"), result)
                answered.countDown()
            }
            assertTrue("native command reached the dispatcher", answered.await(2, TimeUnit.SECONDS))
            val thread = worker.get()
            awaitState(thread, Thread.State.WAITING)
            synchronized(monitor) {
                thread.interrupt()
                awaitState(thread, Thread.State.BLOCKED)
                // Close while the awakened worker cannot reacquire the monitor. No wait is allowed
                // here: only after releasing the monitor can the caller prove that retirement drained.
                assertFalse(rig.commands.stop(MonotonicDeadline(0)))
            }
            assertTrue("idle dispatcher must retire within the caller's budget",
                rig.commands.stop(MonotonicDeadline(2_000)))
            assertEquals(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED),
                submitNative(rig, "volume", "60"))
        } finally {
            // Release a broken baseline worker too, so the intentionally red run leaves no parked thread.
            synchronized(monitor) { monitor.notifyAll() }
            rig.close()
        }
    }

    private fun awaitState(thread: Thread, expected: Thread.State) {
        val deadline = MonotonicDeadline(2_000)
        while (thread.state != expected && deadline.remainingMs() > 0L) Thread.yield()
        assertEquals("scheduler reached the required interleaving", expected, thread.state)
    }
}
