package io.panelassistant.android

import io.panelassistant.android.testsupport.TestSources
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceProcessBoundaryContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal (HiveMqTransport.kt goes with it too), and the command dispatcher's retirement order, which needs the Android service to construct.
    @Test fun mqttConnectedCallbackCannotRunAnnouncementWorkOrPinTheLifecycleGate() {
        val mqtt = source("MqttBridge.kt")
        val callback = mqtt.substring(
            mqtt.indexOf("private fun onConnected("),
            mqtt.indexOf("private fun performConnectionEvent("),
        )
        val disconnectedCallback = mqtt.substring(
            mqtt.indexOf("private fun onDisconnected("),
            mqtt.indexOf("private fun requestReAnnounce("),
        )
        val connectionEvent = mqtt.substring(
            mqtt.indexOf("private fun performConnectionEvent("),
            mqtt.indexOf("private fun performConnectAnnouncement("),
        )
        val announcement = mqtt.substring(
            mqtt.indexOf("private fun performConnectAnnouncement("),
            mqtt.indexOf("private fun connectionAnnouncementIsCurrent("),
        )
        val startOpen = mqtt.substring(
            mqtt.indexOf("private fun startOpen()"),
            mqtt.indexOf("private fun scheduleAuthRetry("),
        )
        val stop = mqtt.substring(mqtt.indexOf("fun stop("), mqtt.indexOf("companion object", mqtt.indexOf("fun stop(")))

        assertTrue(callback.contains("connectionEventDispatcher.submit(MqttConnectionEvent.Connected(connection, addressFamily))"))
        listOf(
            "lifecycle.runIfOpen",
            "discoveryCapabilities.snapshot",
            "transport.subscribe",
            "publishDiscovery",
            "pruneStaleDiscovery",
            "restoreAndPublishStates",
        ).forEach { forbidden -> assertFalse("connected callback contains $forbidden", callback.contains(forbidden)) }
        assertTrue(disconnectedCallback.contains("connectionEventDispatcher.submit("))
        assertTrue(disconnectedCallback.contains("mqttAutomaticReconnectAllowed(classified, admission)"))
        assertFalse(disconnectedCallback.contains("lifecycle.runIfOpen"))
        assertTrue(connectionEvent.contains("lifecycle.runIfOpen"))
        assertTrue(connectionEvent.contains("connectAnnouncementDispatcher.submit(announcement)"))
        assertTrue(
            startOpen.indexOf("connectionEventDispatcher.supersede()") <
                startOpen.indexOf("activeConnection = null"),
        )
        assertTrue(announcement.contains("core.capabilities.snapshot()"))
        assertTrue(announcement.contains("publishDiscovery(capabilitySnapshot)"))
        assertTrue(announcement.contains("publish(availabilityTopic, \"online\", retain = true)"))
        assertFalse(announcement.contains("lifecycle.runIfOpen"))
        assertTrue(stop.contains("connectionEventDispatcher.close()"))
        assertTrue(stop.contains("connectAnnouncementDispatcher.close()"))
        assertTrue(stop.contains("connectionEventDispatcher.closeAndJoin(deadline.remainingMs())"))
        assertTrue(stop.contains("connectAnnouncementDispatcher.closeAndJoin(deadline.remainingMs())"))
        assertTrue(stop.contains("connectAnnouncementDrained"))
    }

    @Test fun mqttRetirementClosesAdmissionBeforeBoundedDrainWithoutHoldingAMutationMonitor() {
        val mqtt = source("MqttBridge.kt")
        assertFalse(Modifier.isSynchronized(MqttBridge::class.java.getDeclaredMethod("start").modifiers))
        assertFalse(Modifier.isSynchronized(
            MqttBridge::class.java.getDeclaredMethod("reconnect").modifiers,
        ))
        MqttBridge::class.java.declaredMethods.filter { it.name in setOf("onConnected", "onDisconnected") }
            .forEach { callback ->
            assertFalse("transport callback holds the bridge monitor: ${callback.name}",
                Modifier.isSynchronized(callback.modifiers))
        }
        listOf("stopLock", "lifecycleGeneration", "@Volatile private var stopped", "stateConvergerOwner")
            .forEach { assertFalse("obsolete MQTT lifecycle state remains: $it", mqtt.contains(it)) }

        val stop = mqtt.substring(mqtt.indexOf("fun stop("), mqtt.indexOf("companion object", mqtt.indexOf("fun stop(")))
        val firstWait = stop.indexOf("lifecycle.awaitDrained(deadline)")
        listOf(
            "lifecycle.closeAdmission()",
            "connectionEventDispatcher.close()",
            "connectAnnouncementDispatcher.close()",
            "stateOutbox.close()",
            "authScheduler.shutdownNow()",
            "adbReassertWorker.closeAndJoin(0L)",
            "reannounceDispatcher.close()",
        ).forEach { step ->
            assertTrue("MQTT retirement signal missing before its first wait: $step",
                stop.indexOf(step) in 0 until firstWait)
        }
        listOf("ownersDrained", "finalization", "deadline.remainingMs()", "transport.publishThenDisconnect(")
            .forEach { assertTrue("typed/shared-deadline MQTT retirement step missing: $it", stop.contains(it)) }

        // The command dispatcher retires the same way: every signal before its first wait.
        val commands = source("PanelCommandDispatcher.kt")
        val commandStop = commands.substring(commands.indexOf("fun stop(deadline: MonotonicDeadline)"), commands.indexOf("companion object", commands.indexOf("fun stop(deadline: MonotonicDeadline)")))
        val commandWait = commandStop.indexOf("lifecycle.awaitDrained(deadline)")
        listOf("lifecycle.closeAdmission()", "queue.close()", "zigbeeWorker.closeAndJoin(0L)").forEach { step ->
            assertTrue("command retirement signal missing before its first wait: $step", commandStop.indexOf(step) in 0 until commandWait)
        }
    }

    @Test fun hiveTransportPublishesTupleTransitionsAndCallbackEnqueuesInOneOrder() {
        val hive = source("mqtt/HiveMqTransport.kt")
        val connected = hive.substring(
            hive.indexOf(".addConnectedListener"),
            hive.indexOf(".addDisconnectedListener"),
        )
        val disconnected = hive.substring(
            hive.indexOf(".addDisconnectedListener"),
            hive.indexOf("// ssl:///mqtts://"),
        )

        assertTrue(connected.contains("synchronized(sessionLock)"))
        assertTrue(connected.indexOf("session = Session(") < connected.indexOf("callbacks.onConnected(lease,"))
        assertTrue(disconnected.contains("synchronized(sessionLock)"))
        assertTrue(disconnected.indexOf("session = Session(") < disconnected.indexOf("callbacks.onDisconnected("))
        assertTrue(
            hive.contains("builder.transportConfig()") &&
                hive.contains(".socketConnectTimeout(") &&
                hive.contains("ADDRESS_FAMILY_CONNECT_TIMEOUT_SECONDS = 10L"),
        )
        assertFalse(hive.contains("@Volatile private var client"))
        assertFalse(hive.contains("@Volatile private var connectionLease"))
    }

    private fun source(relative: String): String = TestSources.kotlin(relative).readText()
}
