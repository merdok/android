package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.util.LatestOperationPolicy
import io.panelassistant.android.util.LatestOperationTimeoutPolicy
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.ServiceRuntimeOwner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renaming a panel changes only what its mDNS advertisement says. The reconfigure the Configure page
 * starts keeps the running state core and command dispatcher, so nothing queued is cancelled and no
 * state is restored or reported again; only the advertiser is replaced with the new name.
 */
class NetworkRuntimeRenameTest {
    @Test fun aRenamedPanelKeepsItsStateCoreAndDispatcherAndAdvertisesTheNewName() {
        val prefs = fakePreferences(mapOf("panel_id" to PANEL, "friendly_name" to "Old name"))
        val config = Config(prefs.instance)
        val service = PaneldService()
        fenceProcessExit(service)
        setField(service, "config", config)
        setField(service, "liveSettingAuthority", LiveSettingAuthority(emptySet(), bootIdentity = { "boot" }))
        setField(service, "appliedNetworkConfiguration", call(service, "currentNetworkConfigurationSnapshot"))

        val core = idleCore(config)
        val commands = idleCommands()
        val previousAdvertiser = MdnsAdvertiser(
            ContextWrapper(null), config, acquireMulticastLock = { {} }, discoveryId = { null },
        )
        val reconfigure = PaneldService::class.java.getDeclaredMethod(
            "performNetworkReconfigure", ServiceRuntimeOwner.LatestMutation::class.java,
        ).apply { isAccessible = true }
        val owner = ServiceRuntimeOwner<PaneldService.NetworkRuntime>(
            initial = PaneldService.NetworkRuntime(core, commands, previousAdvertiser),
            threadName = "rename-test-runtime",
            latestOperation = LatestOperationPolicy(
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 30_000L, schedule = { _, _ -> true }, cancel = {}, onTimeout = {},
                ),
                operation = { reconfigure.invoke(service, this) },
            ),
        )
        setField(service, "runtime", owner)
        setField(service, "mdnsRuntimeReconciler", MdnsRuntimeReconciler(owner) { _, _ -> })
        try {
            assertTrue(owner.start {}.get(5, TimeUnit.SECONDS))

            prefs.values["friendly_name"] = "New name"
            PaneldService::class.java.getDeclaredMethod(
                "enqueueReconfigure", Set::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, setOf("friendly_name"), true)

            val settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < settled &&
                (owner.pendingLatestCount() > 0 || !owner.isRunning() || owner.current().mdns === previousAdvertiser)
            ) {
                Thread.sleep(20)
            }

            assertTrue("the rename must leave the runtime running", owner.isRunning())
            val renamed = owner.current()
            assertSame("a rename must keep the running state core", core, renamed.core)
            assertSame("a rename must keep the running command dispatcher", commands, renamed.commands)
            assertTrue("the kept dispatcher must still admit commands", gate(commands).isOpen())
            assertTrue("the kept state core must still be open", gate(core).isOpen())
            assertNotSame("the advertiser carries the name, so it is replaced", previousAdvertiser, renamed.mdns)
            assertEquals("New name", read(renamed.mdns, "runtimeFriendlyName"))
            val applied = read(service, "appliedNetworkConfiguration") as NetworkConfigurationSnapshot
            assertEquals("New name", applied.runtime.friendlyName)
        } finally {
            owner.shutdown(5_000L) {}
        }
    }

    /** A state core with nothing registered and no Home Assistant address, so a link resolve returns at once. */
    private fun idleCore(config: Config): PanelStateCore = allocate(PanelStateCore::class.java).also { core ->
        setOn(core, "config", config)
        setOn(core, "lifecycle", RetirableMutationGate())
        setOn(core, "converger", io.panelassistant.android.state.StateConverger())
        setOn(core, "haLinkResolutionThread", AtomicReference<Thread?>())
    }

    /** A command dispatcher with nothing queued. */
    private fun idleCommands(): PanelCommandDispatcher = allocate(PanelCommandDispatcher::class.java).also { commands ->
        val zigbee = ZigbeeActuationCoordinator()
        setOn(commands, "lifecycle", RetirableMutationGate())
        setOn(commands, "zigbeeActuation", zigbee)
        setOn(commands, "zigbeeLease", zigbee.activate())
        setOn(commands, "queue", CommandQueue())
        setOn(commands, "operations", ManageOperations(ManageTable(), { io.panelassistant.android.security.LocalApprovalBroker.instance }))
        setOn(commands, "zigbeeWorker", io.panelassistant.android.util.LatestDispatcher.singleSlot<Boolean>("rename-test-zigbee", consume = {}))
    }

    private fun gate(target: Any): RetirableMutationGate = read(target, "lifecycle") as RetirableMutationGate

    /** The finalizer ends in `exitProcess` on an incomplete teardown; claiming its recovery slot keeps the JVM. */
    private fun fenceProcessExit(service: PaneldService) {
        val boundary = read(service, "teardownBoundary") as ServiceTeardownBoundary
        assertTrue(boundary.recordCompletionAndClaimRecovery(false))
    }

    private fun call(service: PaneldService, name: String): Any? =
        PaneldService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)

    private fun read(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setOn(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun setField(service: PaneldService, name: String, value: Any?) {
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object {
        val PANEL = "rename-test-panel-${ProcessHandle.current().pid()}"
    }
}
