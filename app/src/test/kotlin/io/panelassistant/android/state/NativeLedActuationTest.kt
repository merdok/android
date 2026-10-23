package io.panelassistant.android.state

import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

internal class NativeLedActuationTest : PanelRuntimeRig() {
    private class Led : LedController {
        var accepted = true
        override fun available() = true
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int) = accepted
        override fun off() = accepted
    }

    @Test fun refusedOutputWithdrawsKnownStateAndSuccessfulRetryRestoresIt() {
        val led = Led()
        val rig = rig(led = led)
        val states = CopyOnWriteArrayList<StateConverger.Observation>()
        try {
            rig.core.addStateSink { channel, observation -> if (channel == "led") states += observation }
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "led", CommandValue.Light(true, rgb = Triple(200, 0, 0))))
            assertTrue(states.last() is StateConverger.Observation.Known)
            led.accepted = false
            val result = submitNative(rig, "led", CommandValue.Light(false))
            assertTrue("refused output fails the native command: $result", result is PanelAssistantCommandResult.Failed)
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals(StateConverger.Observation.Unavailable, states.last())
            assertEquals(PanelAssistantCommandResult.Applied, run { led.accepted = true; submitNative(rig, "led", CommandValue.Light(false)) })
            assertTrue(states.last() is StateConverger.Observation.Known)
        } finally { rig.close() }
    }
    @Test fun aLaterEffectFrameFailureWithdrawsThePreviouslyKnownActiveEffect() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        var armed = false
        val led = object : LedController {
            override fun available() = true
            override fun colorCapable() = true
            fun write(): Boolean {
                if (!armed || writes.incrementAndGet() == 1) return true
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return false
            }
            override fun setRgb(r: Int, g: Int, b: Int) = write()
            override fun off() = write()
        }
        val rig = rig(led = led)
        val states = CopyOnWriteArrayList<StateConverger.Observation>()
        try {
            rig.core.addStateSink { channel, observation -> if (channel == "led") states += observation }
            rig.start()
            armed = true
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "led", CommandValue.Light(true, effect = "pulse")))
            val known = states.last() as StateConverger.Observation.Known
            assertEquals("pulse", JSONObject(known.payload).getString("effect"))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            val until = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < until && states.last() != StateConverger.Observation.Unavailable) {
                rig.core.reconcileAll()
                Thread.sleep(5)
            }
            assertEquals(StateConverger.Observation.Unavailable, states.last())
        } finally { release.countDown(); rig.close() }
    }

}
