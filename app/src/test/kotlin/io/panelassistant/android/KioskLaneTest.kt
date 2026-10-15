package io.panelassistant.android

import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.MonotonicDeadline
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiosk lock has one lane: the :8888 toggle, import and restore, the on-panel escape gesture, Panel
 * Assistant's switch and the start-up re-lock all run on the command worker, in the order they were asked
 * for, through the same entry the panel's own doors use for every live setting.
 */
internal class KioskLaneTest : PanelRuntimeRig() {
    private val applied = CopyOnWriteArrayList<Boolean>()

    private fun kioskRig(canEnable: Boolean = true, recover: (Boolean) -> Boolean = { true }) = rig(
        kioskApply = { on -> applied += on; !on || canEnable },
        kioskRecover = recover,
    )

    private fun authority() = LiveSettingAuthority(PanelCommandDispatcher.APPLY_SETTING_KEYS, bootIdentity = { "boot" })

    private fun request(rig: Rig, authority: LiveSettingAuthority, value: String): LiveSettingRequestOutcome =
        requestLiveSetting(authority, rig.config, { rig.commands }, "kiosk_lock", value) {}

    /** Holds the ordered worker on a relay write until [release]. */
    private fun holdWorker(rig: Rig): Thread {
        rig.sysfs.blockNextWrite("$RELAY_BASE/relay1")
        val held = thread { submit(rig, "relay1", "ON") }
        assertTrue("worker held", rig.sysfs.blockEntered.await(10, TimeUnit.SECONDS))
        return held
    }

    private fun release(rig: Rig, vararg threads: Thread) {
        rig.sysfs.blockRelease.countDown()
        threads.forEach { it.join(20_000) }
    }

    private fun submit(rig: Rig, channel: String, payload: String): PanelAssistantCommandResult {
        val answer = LinkedBlockingQueue<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, nativeValue(channel, payload), admit = { null }), CommandOrigin.panelAssistant()) { answer.put(it) }
        return requireNotNull(answer.poll(20, TimeUnit.SECONDS)) { "$channel answered" }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition never held" }
            Thread.sleep(5)
        }
    }

    @Test fun aKioskToggleQueuedBehindABlockedCommandWaitsForTheWorker() {
        val rig = kioskRig()
        val authority = authority()
        try {
            val held = holdWorker(rig)

            // The request answers once its bounded wait ends: still queued, kept for replay, nothing locked.
            assertEquals(LiveSettingRequestOutcome.DEFERRED, request(rig, authority, "true"))
            assertEquals(emptyList<Boolean>(), applied.toList())
            assertFalse(rig.config.kioskLock)
            assertEquals(setOf("kiosk_lock"), authority.pendingSnapshot().keys)

            release(rig, held)
            waitUntil { authority.pendingSnapshot().isEmpty() }
            assertEquals(listOf(true), applied.toList())
            assertTrue(rig.config.kioskLock)
        } finally {
            rig.close()
        }
    }

    @Test fun theEscapeOffIsDurableAtOnceAndReplacesAQueuedOn() {
        val rig = kioskRig()
        val authority = authority()
        try {
            assertTrue(rig.config.commitKioskLock(true))
            val held = holdWorker(rig)
            val on = AtomicReference<LiveSettingRequestOutcome>()
            val onRequest = thread { on.set(request(rig, authority, "true")) }
            onRequest.join(10_000)

            // The gesture's OFF: stored before the worker reaches it, so a restart now comes back unlocked.
            assertEquals(LiveSettingRequestOutcome.DEFERRED, request(rig, authority, "false"))
            assertFalse(rig.config.kioskLock)
            assertEquals(emptyList<Boolean>(), applied.toList())

            release(rig, held)
            waitUntil { authority.pendingSnapshot().isEmpty() }
            assertEquals("the queued ON never ran", listOf(false), applied.toList())
            assertFalse(rig.config.kioskLock)
        } finally {
            rig.close()
        }
    }

    @Test fun anOnThePanelCannotEnableStoresNothing() {
        val rig = kioskRig(canEnable = false)
        try {
            assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, request(rig, authority(), "true"))
            assertEquals(listOf(true), applied.toList())
            assertFalse(rig.config.kioskLock)
        } finally {
            rig.close()
        }
    }

    @Test fun startUpReLockNeverOverridesAQueuedOff() {
        val rig = kioskRig()
        try {
            assertTrue(rig.config.commitKioskLock(true))
            val held = holdWorker(rig)
            val off = LinkedBlockingQueue<PanelAssistantCommandResult>()
            rig.commands.submit(PanelAssistantCommand("kiosk_lock", CommandValue.Switch(false), admit = { null }), CommandOrigin.panelAssistant()) { off.put(it) }
            val relocked = AtomicReference<Boolean?>()
            val relock = thread { relocked.set(rig.commands.recoverKiosk(enable = true)) }
            // Queued behind the OFF and waiting for the worker, or already finished if it bypassed the queue.
            waitUntil { relock.state == Thread.State.WAITING || relock.state == Thread.State.TIMED_WAITING || !relock.isAlive }

            release(rig, held, relock)
            assertEquals(PanelAssistantCommandResult.Applied, off.poll(10, TimeUnit.SECONDS))
            assertEquals(false, relocked.get())
            assertEquals(listOf(false), applied.toList())
            assertFalse(rig.config.kioskLock)
        } finally {
            rig.close()
        }
    }

    @Test fun startUpRecoveryRunsOnTheWorkerForTheStoredSetting() {
        val recovered = CopyOnWriteArrayList<Boolean>()
        val rig = kioskRig(recover = { legacy -> recovered += legacy; true })
        try {
            assertTrue(rig.config.commitKioskLock(true))
            assertEquals(true, rig.commands.recoverKiosk(enable = false))
            assertEquals(listOf(true), recovered.toList())
            assertEquals(true, rig.commands.recoverKiosk(enable = true))
            assertEquals(listOf(true), applied.toList())
        } finally {
            rig.close()
        }
    }

    @Test fun aRecoveryTheWorkerDidNotRunAnswersNull() {
        val rig = kioskRig()
        try {
            holdWorker(rig)
            val queued = AtomicReference<Boolean?>(true)
            val waiting = thread { queued.set(rig.commands.recoverKiosk(enable = true)) }
            waitUntil { waiting.state == Thread.State.WAITING || waiting.state == Thread.State.TIMED_WAITING }

            val stopper = thread { rig.commands.stop(MonotonicDeadline(5_000)) }
            waiting.join(10_000)
            assertFalse("a cancelled recovery must not hang", waiting.isAlive)
            assertNull(queued.get())
            assertNull("a stopped dispatcher runs no recovery", rig.commands.recoverKiosk(enable = false))
            release(rig, stopper)
            assertEquals(emptyList<Boolean>(), applied.toList())
        } finally {
            rig.close()
        }
    }
}
