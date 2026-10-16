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
 * for. A lock is never journalled for replay; an unlock is stored before it queues.
 */
internal class KioskLaneTest : PanelRuntimeRig() {
    private val applied = CopyOnWriteArrayList<Boolean>()

    private fun kioskRig(canEnable: Boolean = true, recover: (Boolean) -> Boolean = { true }) = rig(
        kioskApply = { on -> applied += on; !on || canEnable },
        kioskRecover = recover,
    )

    private fun authority() = LiveSettingAuthority(PanelCommandDispatcher.APPLY_SETTING_KEYS, bootIdentity = { "boot" })

    /** What the gesture's or :8888's fallback unlock was asked to do. */
    private val unlocked = CopyOnWriteArrayList<Boolean>()

    private fun request(rig: Rig, authority: LiveSettingAuthority, value: String): LiveSettingRequestOutcome =
        requestLiveSetting(authority, rig.config, { rig.commands }, "kiosk_lock", value, { unlocked += true; true }) {}

    private fun escape(rig: Rig): LiveSettingRequestOutcome =
        requestKioskSetting(rig.config, { rig.commands }, "false") { unlocked += true; true }

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

            // The request answers once its bounded wait ends: still queued, nothing locked, nothing journalled.
            assertEquals(LiveSettingRequestOutcome.DEFERRED, request(rig, authority, "true"))
            assertEquals(emptyList<Boolean>(), applied.toList())
            assertFalse(rig.config.kioskLock)
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())

            release(rig, held)
            waitUntil { rig.config.kioskLock }
            assertEquals(listOf(true), applied.toList())
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
            thread { on.set(request(rig, authority, "true")) }.join(10_000)
            assertEquals(LiveSettingRequestOutcome.DEFERRED, on.get())

            // The gesture's OFF: stored before the worker reaches it, so a restart now comes back unlocked.
            assertEquals(LiveSettingRequestOutcome.DEFERRED, escape(rig))
            assertEquals("the worker unlocks, not the fallback", emptyList<Boolean>(), unlocked.toList())
            assertFalse(rig.config.kioskLock)
            assertEquals(emptyList<Boolean>(), applied.toList())

            release(rig, held)
            waitUntil { applied.isNotEmpty() }
            assertEquals("the queued ON never ran", listOf(false), applied.toList())
            assertFalse(rig.config.kioskLock)
        } finally {
            rig.close()
        }
    }

    /** A rig whose kiosk lock blocks inside its actuator until [release], ignoring the worker's interrupt as a root shell does. */
    private fun lockingRig(locking: java.util.concurrent.CountDownLatch, release: java.util.concurrent.CountDownLatch) = rig(
        kioskApply = { on ->
            if (on) {
                locking.countDown()
                while (true) try { release.await(); break } catch (_: InterruptedException) {}
            }
            applied += on
            true
        },
    )

    @Test fun anOffAcceptedWhileAnOlderHttpOnIsLockingStaysOffAndNothingReplaysTheOn() {
        val locking = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val rig = lockingRig(locking, release)
        // A journal that refuses every write: kiosk never depends on it in either direction.
        val authority = LiveSettingAuthority(PanelCommandDispatcher.APPLY_SETTING_KEYS, object : LiveSettingAuthority.Journal {
            override fun load() = emptyMap<String, LiveSettingAuthority.Pending>()
            override fun put(key: String, value: LiveSettingAuthority.Pending) = false
            override fun remove(key: String) = true
        }, bootIdentity = { "boot" })
        try {
            assertTrue(rig.config.commitKioskLock(false))
            val on = AtomicReference<LiveSettingRequestOutcome>()
            val onRequest = thread { on.set(request(rig, authority, "true")) }
            assertTrue("the ON is locking", locking.await(10, TimeUnit.SECONDS))
            onRequest.join(10_000)
            assertEquals(LiveSettingRequestOutcome.DEFERRED, on.get())

            // The escape is accepted and stored while the ON still locks; its command queues behind it.
            assertEquals(LiveSettingRequestOutcome.DEFERRED, escape(rig))
            assertFalse(rig.config.kioskLock)

            // The worker stops before the queued OFF runs, as a process end would; the ON then finishes.
            val stopping = thread { rig.commands.stop(MonotonicDeadline(20_000)) }
            waitUntil { stopping.state == Thread.State.WAITING || stopping.state == Thread.State.TIMED_WAITING || !stopping.isAlive }
            release.countDown()
            stopping.join(20_000)

            assertFalse("the newer OFF stays the stored truth", rig.config.kioskLock)
            assertEquals("locked, then rolled back; the OFF never ran", listOf(true, false), applied.toList())
            var replayed = 0
            authority.replay { _, _ -> replayed++; LiveSettingApplyResult.APPLIED }
            assertEquals("no kiosk value is left to replay", 0, replayed)
        } finally {
            release.countDown()
            rig.close()
        }
    }

    @Test fun anOffAcceptedWhileAnOlderPanelAssistantOnIsLockingStaysStored() {
        val locking = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val rig = lockingRig(locking, release)
        try {
            val on = LinkedBlockingQueue<PanelAssistantCommandResult>()
            rig.commands.submit(PanelAssistantCommand("kiosk_lock", CommandValue.Switch(true), admit = { null }), CommandOrigin.panelAssistant()) { on.put(it) }
            assertTrue("the ON is locking", locking.await(10, TimeUnit.SECONDS))
            assertEquals(LiveSettingRequestOutcome.DEFERRED, escape(rig))

            val stopping = thread { rig.commands.stop(MonotonicDeadline(20_000)) }
            waitUntil { stopping.state == Thread.State.WAITING || stopping.state == Thread.State.TIMED_WAITING || !stopping.isAlive }
            release.countDown()
            stopping.join(20_000)

            assertTrue("the older ON yields", on.poll(10, TimeUnit.SECONDS) is PanelAssistantCommandResult.Failed)
            assertFalse("the newer OFF stays the stored truth", rig.config.kioskLock)
        } finally {
            release.countDown()
            rig.close()
        }
    }

    @Test fun anOnThePanelCannotEnableIsRefusedAndNotKept() {
        val rig = kioskRig(canEnable = false)
        val authority = authority()
        try {
            assertEquals(LiveSettingRequestOutcome.REJECTED, request(rig, authority, "true"))
            assertEquals(listOf(true), applied.toList())
            assertFalse(rig.config.kioskLock)
            assertEquals(emptyMap<String, String>(), authority.pendingSnapshot())
        } finally {
            rig.close()
        }
    }

    @Test fun anOffTheStoreRefusesStillUnlocks() {
        val rig = kioskRig()
        val refusing = Config(fakePreferences(commitSucceeds = false).instance)
        try {
            requestKioskSetting(refusing, { rig.commands }, "false") { unlocked += true; true }
            assertEquals(listOf(false), applied.toList())
        } finally {
            rig.close()
        }
    }

    @Test fun anUnlockNoWorkerTakesClearsTheLockHereAndStaysStored() {
        val rig = kioskRig()
        try {
            assertTrue(rig.config.commitKioskLock(true))
            assertTrue(rig.commands.stop(MonotonicDeadline(5_000)))

            assertEquals(LiveSettingRequestOutcome.APPLIED, escape(rig))
            assertEquals(listOf(true), unlocked.toList())
            assertFalse(rig.config.kioskLock)
            assertEquals(LiveSettingRequestOutcome.REJECTED, request(rig, authority(), "true"))
            assertFalse("a lock no worker takes is refused, not stored", rig.config.kioskLock)
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
