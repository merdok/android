package io.panelassistant.android.platform

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import io.panelassistant.android.AppIdentity
import io.panelassistant.android.camera.CameraCapabilityReason
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.platform.PanelPermissionRepair.Grant
import io.panelassistant.android.platform.PanelPermissionRepair.Outcome
import io.panelassistant.android.platform.PanelPermissionRepair.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPermissionRepairTest {
    private class Panel(
        val permissions: MutableSet<Grant>,
        private val reply: DaemonLongResult = DaemonLongResult.Reply("OK"),
        private val keepGrants: Boolean = true,
    ) : Daemon {
        val submitted = mutableListOf<String>()
        val observed = mutableSetOf<Grant>()
        fun held(grant: Grant): Boolean {
            observed += grant
            return grant in permissions
        }
        override fun available() = reply != DaemonLongResult.NotSubmitted
        override fun send(cmd: String): String? = error("unexpected $cmd")
        override fun sendBytes(cmd: String): ByteArray? = error("unexpected $cmd")
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
            val grant = Grant.valueOf(cmd.substringAfterLast(' '))
            assertTrue("a missing grant must be read before it is repaired", grant in observed)
            assertFalse("an existing grant must never be submitted", grant in permissions)
            submitted += cmd
            if (keepGrants && reply != DaemonLongResult.NotSubmitted) permissions += grant
            return reply
        }
    }

    private fun repair(panel: Panel, sdk: Int = 34, microphone: Boolean = true, camera: Boolean = true) =
        PanelPermissionRepair.repair(sdk, microphone, camera, panel::held, panel, AppIdentity.OWN)

    @Test fun updateRepairsOnlyMissingFeatureGrantsAndARepeatWritesNothing() {
        for (pkg in listOf(AppIdentity.OWN, AppIdentity.LEGACY)) {
            val present = setOf(Grant.NOTIFICATIONS, Grant.OVERLAY, Grant.MICROPHONE)
            val panel = Panel(present.toMutableSet())
            val result = PanelPermissionRepair.repair(34, true, true, panel::held, panel, pkg)
            assertEquals(Grant.entries.toSet(), panel.permissions)
            assertEquals(present.associateWith { Outcome.HELD } +
                (Grant.entries.toSet() - present).associateWith { Outcome.CLAIMED }, result)
            assertEquals(listOf("GRANT $pkg WRITESETTINGS", "GRANT $pkg ACCESSIBILITY", "GRANT $pkg CAMERA"), panel.submitted)
            panel.submitted.clear()
            assertEquals(Grant.entries.associateWith { Outcome.HELD },
                PanelPermissionRepair.repair(34, true, true, panel::held, panel, pkg))
            assertTrue(panel.submitted.isEmpty())
        }
    }

    @Test fun supportedHardwareAndAndroidVersionChooseTheGrants() {
        for (sdk in listOf(32, 33, 34)) for (microphone in listOf(false, true)) for (camera in listOf(false, true)) {
            val panel = Panel(mutableSetOf())
            val expected = setOf(Grant.WRITESETTINGS, Grant.OVERLAY, Grant.ACCESSIBILITY) +
                (if (sdk >= 33) setOf(Grant.NOTIFICATIONS) else emptySet()) +
                (if (microphone) setOf(Grant.MICROPHONE) else emptySet()) +
                (if (camera) setOf(Grant.CAMERA) else emptySet())
            assertEquals(expected.associateWith { Outcome.CLAIMED }, repair(panel, sdk, microphone, camera))
            assertEquals(expected, panel.permissions)
            assertEquals(expected.size, panel.submitted.size)
        }
        assertTrue(PanelPermissionRepair.notificationsHeld(32) { false })
        assertFalse(PanelPermissionRepair.notificationsHeld(33) { false })
    }

    @Test fun missingHelperOrRefusedReadbackDoesNotClaimGrants() {
        for (reply in listOf(DaemonLongResult.NotSubmitted, DaemonLongResult.Reply("ERR"),
            DaemonLongResult.Reply("OK"), DaemonLongResult.Indeterminate)) {
            val panel = Panel(mutableSetOf(), reply, keepGrants = false)
            val expected = if (reply == DaemonLongResult.NotSubmitted) Outcome.NO_HELPER else Outcome.REFUSED
            assertEquals(Grant.entries.associateWith { expected }, repair(panel))
            assertTrue(panel.permissions.isEmpty())
        }
    }

    @Test fun aLostReplyIsReconciledFromAndroidsReadback() {
        val panel = Panel(mutableSetOf(), DaemonLongResult.Indeterminate)
        assertEquals(Grant.entries.associateWith { Outcome.CLAIMED }, repair(panel))
        assertEquals(Grant.entries.toSet(), panel.permissions)
    }

    @Test fun unreadableStateIsNotTreatedAsAMissingGrant() {
        val panel = Panel(Grant.entries.toMutableSet())
        val outcomes = PanelPermissionRepair.repair(34, true, true, { grant ->
            if (grant == Grant.CAMERA) throw SecurityException("readback unavailable")
            panel.held(grant)
        }, panel, AppIdentity.OWN)
        assertEquals(Outcome.UNREADABLE, outcomes[Grant.CAMERA])
        assertEquals(5, outcomes.values.count { it == Outcome.HELD })
        assertTrue(panel.submitted.isEmpty())
    }

    @Test fun cameraMetadataCoversDelayedEnumerationWithoutOverridingTheProfile() {
        for (reason in CameraCapabilityReason.entries) for (hardware in listOf(false, true, null)) {
            val expected = when {
                reason.capable -> true
                reason != CameraCapabilityReason.UNDETERMINED -> false
                hardware == true -> true
                else -> null
            }
            assertEquals(expected, PanelPermissionRepair.cameraRequired(reason, hardware))
        }
    }

    @Test fun statusObservesEachSupportedGrantWithoutRepairingOrReadingAbsentHardware() {
        val panel = Panel(mutableSetOf(Grant.WRITESETTINGS, Grant.ACCESSIBILITY))
        val observed = PanelPermissionRepair.observe(32, false, false, panel::held)
        assertEquals(mapOf(
            Grant.NOTIFICATIONS to State.NOT_REQUIRED,
            Grant.WRITESETTINGS to State.HELD,
            Grant.OVERLAY to State.MISSING,
            Grant.ACCESSIBILITY to State.HELD,
            Grant.MICROPHONE to State.NOT_REQUIRED,
            Grant.CAMERA to State.NOT_REQUIRED,
        ), observed)
        assertEquals(setOf(Grant.WRITESETTINGS, Grant.OVERLAY, Grant.ACCESSIBILITY), panel.observed)
        assertTrue(panel.submitted.isEmpty())
    }

    @Test fun missingUnreadableAndUnknownHardwareRemainDistinct() {
        for (camera in listOf(false, true, null)) {
            val observed = PanelPermissionRepair.observe(34, true, camera) { grant ->
                if (grant == Grant.MICROPHONE) throw SecurityException("read unavailable")
                false
            }
            assertEquals(State.MISSING, observed[Grant.NOTIFICATIONS])
            assertEquals(State.MISSING, observed[Grant.WRITESETTINGS])
            assertEquals(State.MISSING, observed[Grant.OVERLAY])
            assertEquals(State.MISSING, observed[Grant.ACCESSIBILITY])
            assertEquals(State.UNREADABLE, observed[Grant.MICROPHONE])
            assertEquals(when (camera) {
                true -> State.MISSING
                false -> State.NOT_REQUIRED
                null -> State.UNREADABLE
            }, observed[Grant.CAMERA])
        }
        val panel = Panel(mutableSetOf())
        val outcome = PanelPermissionRepair.repair(32, false, null, panel::held, panel, AppIdentity.LEGACY)
        assertEquals(Outcome.UNREADABLE, outcome[Grant.CAMERA])
        assertEquals(setOf(Grant.WRITESETTINGS, Grant.OVERLAY, Grant.ACCESSIBILITY), panel.permissions)
    }

    @Test fun androidRuntimeReadbackChangesWithoutCachingAndHardwareProbeFailureIsPartial() {
        val runtimePermissions = mutableSetOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val context = object : ContextWrapper(null) {
            override fun getPackageName() = AppIdentity.LEGACY
            override fun checkSelfPermission(permission: String): Int =
                if (permission in runtimePermissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            override fun getPackageManager(): PackageManager = throw SecurityException("hardware metadata unavailable")
        }
        val present = PanelPermissionRepair.observe(context, true, CameraCapabilityReason.PRESENT)
        assertEquals(State.HELD, present[Grant.CAMERA])
        assertEquals(State.HELD, present[Grant.MICROPHONE])
        runtimePermissions.clear()
        val missing = PanelPermissionRepair.observe(context, true, CameraCapabilityReason.PRESENT)
        assertEquals(State.MISSING, missing[Grant.CAMERA])
        assertEquals(State.MISSING, missing[Grant.MICROPHONE])
        val unavailable = PanelPermissionRepair.observe(context, true, CameraCapabilityReason.UNDETERMINED)
        assertEquals(State.UNREADABLE, unavailable[Grant.CAMERA])
        assertEquals(State.MISSING, unavailable[Grant.MICROPHONE])
        val suppressed = PanelPermissionRepair.observe(context, false, CameraCapabilityReason.SUPPRESSED_BY_PROFILE)
        assertEquals(State.NOT_REQUIRED, suppressed[Grant.CAMERA])
        assertEquals(State.NOT_REQUIRED, suppressed[Grant.MICROPHONE])
    }

    @Test fun failedHelperAndLostReadbackDoNotAbortOtherGrantRepairs() {
        val panel = Panel(mutableSetOf())
        val helper = object : Daemon by panel {
            override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
                if (cmd.endsWith("WRITESETTINGS")) throw IllegalStateException("helper unavailable")
                return panel.sendLong(cmd, timeoutMs)
            }
        }
        var cameraReads = 0
        val result = PanelPermissionRepair.repair(34, true, true, { grant ->
            if (grant == Grant.CAMERA && ++cameraReads == 2) throw SecurityException("lost readback")
            panel.held(grant)
        }, helper, AppIdentity.LEGACY)
        assertEquals(Outcome.REFUSED, result[Grant.WRITESETTINGS])
        assertEquals(Outcome.UNREADABLE, result[Grant.CAMERA])
        assertEquals(4, result.values.count { it == Outcome.CLAIMED })
        assertEquals(Grant.entries.toSet() - Grant.WRITESETTINGS, panel.permissions)
    }

    @Test fun theFirstStartAfterThePackageMoveRewritesTheAccessibilityEntry() {
        // A panel updated from a build whose classes lived in io.github.maxlyth.hapaneld keeps an entry
        // naming a class that no longer exists. Startup reads it as missing, asks the helper, and claims
        // the grant only once Android's list names the moved class.
        val pkg = AppIdentity.OWN
        var services = "com.vendor/.Reader:$pkg/io.github.maxlyth.hapaneld.input.PanelAccessibilityService"
        val submitted = mutableListOf<String>()
        val helper = object : Daemon {
            override fun available() = true
            override fun send(cmd: String): String? = error("unexpected $cmd")
            override fun sendBytes(cmd: String): ByteArray? = error("unexpected $cmd")
            override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
                submitted += cmd
                // What the root helper's GRANT ACCESSIBILITY does: append its compiled-in component.
                services += ":$pkg/io.panelassistant.android.input.PanelAccessibilityService"
                return DaemonLongResult.Reply("OK")
            }
        }
        val held = { grant: Grant ->
            grant != Grant.ACCESSIBILITY || PanelPermissionRepair.accessibilityHeld(pkg, services, true)
        }
        assertEquals(Outcome.CLAIMED, PanelPermissionRepair.repair(34, true, true, held, helper, pkg)[Grant.ACCESSIBILITY])
        assertEquals(listOf("GRANT $pkg ACCESSIBILITY"), submitted)
        assertEquals(Outcome.HELD, PanelPermissionRepair.repair(34, true, true, held, helper, pkg)[Grant.ACCESSIBILITY])
        assertEquals("a second start writes nothing", 1, submitted.size)
    }

    private val stale = "com.vendor/.Reader:${AppIdentity.OWN}/io.github.maxlyth.hapaneld.input.PanelAccessibilityService"
    private val moved = "${AppIdentity.OWN}/io.panelassistant.android.input.PanelAccessibilityService"

    /** A panel whose accessibility list is changed only by what the root shell runs. */
    private class RootedPanel(var services: String, var enabled: Boolean = false, available: Boolean = true) {
        val root = FakeRootShell(
            outputs = mapOf("settings get secure enabled_accessibility_services" to "$services\n"),
            available = available,
            onRun = { cmd ->
                when {
                    cmd.startsWith("settings put secure enabled_accessibility_services ") ->
                        services = cmd.removePrefix("settings put secure enabled_accessibility_services ")
                    cmd == "settings put secure accessibility_enabled 1" -> enabled = true
                }
            },
        )
        fun held(grant: Grant) =
            grant != Grant.ACCESSIBILITY || PanelPermissionRepair.accessibilityHeld(AppIdentity.OWN, services, enabled)
    }

    /** A helper that cannot add the moved class: absent, or installed before the package move. */
    private fun helper(reply: DaemonLongResult) = object : Daemon {
        override fun available() = reply != DaemonLongResult.NotSubmitted
        override fun send(cmd: String): String? = error("unexpected $cmd")
        override fun sendBytes(cmd: String): ByteArray? = error("unexpected $cmd")
        override fun sendLong(cmd: String, timeoutMs: Long) = reply
    }

    @Test fun withoutAHelperThatCanGrantItSuAppendsTheMovedAccessibilityClass() {
        for (reply in listOf(DaemonLongResult.NotSubmitted, DaemonLongResult.Reply("OK"))) {
            val panel = RootedPanel(stale)
            val outcome = PanelPermissionRepair.repair(34, true, true, panel::held, helper(reply), AppIdentity.OWN, panel.root)
            assertEquals("helper $reply", Outcome.CLAIMED, outcome[Grant.ACCESSIBILITY])
            assertEquals("$stale:$moved", panel.services)
            assertTrue(panel.enabled)
            assertEquals("a second start writes nothing", Outcome.HELD,
                PanelPermissionRepair.repair(34, true, true, panel::held, helper(reply), AppIdentity.OWN, panel.root)[Grant.ACCESSIBILITY])
            assertEquals(2, panel.root.ran.size)
        }
    }

    @Test fun anUnsetListGetsTheComponentAloneAndNoSuLeavesTheOutcomeAsItWas() {
        val unset = RootedPanel("null")
        PanelPermissionRepair.repair(34, true, true, unset::held, helper(DaemonLongResult.NotSubmitted), AppIdentity.OWN, unset.root)
        assertEquals(moved, unset.services)

        val noSu = RootedPanel(stale, available = false)
        assertEquals(Outcome.NO_HELPER, PanelPermissionRepair.repair(34, true, true, noSu::held,
            helper(DaemonLongResult.NotSubmitted), AppIdentity.OWN, noSu.root)[Grant.ACCESSIBILITY])
        assertTrue(noSu.root.ran.isEmpty())
    }

    @Test fun aListSuCannotAccountForIsNeverWrittenBack() {
        val panel = RootedPanel("com.vendor/.A;reboot")
        assertEquals(Outcome.NO_HELPER, PanelPermissionRepair.repair(34, true, true, panel::held,
            helper(DaemonLongResult.NotSubmitted), AppIdentity.OWN, panel.root)[Grant.ACCESSIBILITY])
        assertTrue(panel.root.ran.isEmpty())
    }

    /** Android clears crashed services only after observing a changed set without their component. */
    private inner class RootlessPanel(var services: String?, var crashed: Boolean = true) {
        val writes = mutableListOf<String>()
        var enabled = false
        var allowWrites = true
        var refusedWrite: Int? = null
        var duringRemoval: () -> Unit = {}
        private fun components() = services.orEmpty().split(':').filter { it.isNotBlank() }.map {
            if (PanelPermissionRepair.accessibilityHeld(AppIdentity.OWN, it, true)) moved else it
        }.toSet()
        private var observed = components()
        private fun observe() {
            val current = components()
            if (current != observed) {
                if (moved !in current) crashed = false
                observed = current
            }
            enabled = moved in current && !crashed
        }
        fun held(grant: Grant): Boolean {
            observe()
            return grant != Grant.ACCESSIBILITY ||
                PanelPermissionRepair.accessibilityHeld(AppIdentity.OWN, services, enabled)
        }
        fun repair() = PanelPermissionRepair.repair(30, false, false, ::held,
            helper(DaemonLongResult.NotSubmitted), AppIdentity.OWN,
            repairAccessibility = {
                PanelPermissionRepair.reenableAccessibility(AppIdentity.OWN,
                    { services },
                    { value ->
                        writes += value
                        val accepted = allowWrites && writes.size != refusedWrite
                        if (accepted) services = value
                        accepted
                    },
                    { observe(); duringRemoval() },
                )
            },
        )[Grant.ACCESSIBILITY]
    }

    @Test fun startupRetriesARootlessCrashedServiceAndARepeatWritesNothing() {
        for (component in listOf(moved, "${AppIdentity.OWN}/.input.PanelAccessibilityService")) {
            val panel = RootlessPanel("com.vendor/.Reader:$component")
            assertEquals(Outcome.CLAIMED, panel.repair())
            assertTrue(panel.enabled)
            assertFalse(panel.crashed)
            assertEquals("com.vendor/.Reader:$moved", panel.services)
            assertEquals(listOf("com.vendor/.Reader", "com.vendor/.Reader:$moved"), panel.writes)
            assertEquals(Outcome.HELD, panel.repair())
            assertEquals(2, panel.writes.size)
        }
    }

    @Test fun startupRecoversAForceStoppedServiceAbsentFromTheEnabledSet() {
        for (services in listOf(null, "com.vendor/.Reader")) {
            val panel = RootlessPanel(services)
            assertEquals(Outcome.CLAIMED, panel.repair())
            assertTrue(panel.enabled)
            assertFalse(panel.crashed)
            assertEquals(listOfNotNull(services, moved).joinToString(":"), panel.services)
            val writes = panel.writes.toList()
            assertEquals(Outcome.HELD, panel.repair())
            assertEquals(writes, panel.writes)
        }
    }

    @Test fun startupPreservesServicesEnabledDuringEitherRecoveryGap() {
        val panel = RootlessPanel(null)
        var gap = 0
        panel.duringRemoval = { panel.services = listOfNotNull(panel.services?.takeIf { it.isNotBlank() }, "com.other/.Reader${++gap}").joinToString(":") }
        assertEquals(Outcome.CLAIMED, panel.repair())
        assertTrue(panel.enabled)
        assertEquals("com.other/.Reader1:com.other/.Reader2:$moved", panel.services)
    }

    @Test fun startupRestoresItsComponentWhenEitherRecoveryWaitFails() {
        for (failedGap in 1..2) {
            val panel = RootlessPanel("com.vendor/.Reader")
            var gap = 0
            panel.duringRemoval = { if (++gap == failedGap) throw InterruptedException("startup interrupted") }
            panel.repair()
            assertEquals(failedGap, gap)
            assertEquals("com.vendor/.Reader:$moved", panel.services)
        }
    }

    @Test fun refusedSecureSettingsLeaveTheServiceListAndOutcomeUnchanged() {
        for (services in listOf("com.vendor/.Reader", "com.vendor/.Reader:$moved")) {
            val panel = RootlessPanel(services)
            panel.allowWrites = false
            assertEquals(Outcome.NO_HELPER, panel.repair())
            assertEquals(services, panel.services)
            assertFalse(panel.enabled)
            assertEquals(1, panel.writes.size)
        }
    }

    @Test fun refusedTransitionsNeverClaimRecoveryAndCanBeRetried() {
        for (refused in 1..3) {
            val panel = RootlessPanel("com.vendor/.Reader")
            panel.refusedWrite = refused
            assertEquals(Outcome.NO_HELPER, panel.repair())
            assertFalse(panel.enabled)
            assertTrue(panel.services.orEmpty().split(':').contains("com.vendor/.Reader"))
            assertEquals(refused, panel.writes.size)
            panel.refusedWrite = null
            assertEquals(Outcome.CLAIMED, panel.repair())
            assertTrue(panel.enabled)
        }
    }

    @Test fun startupAddsAnAbsentComponentWithoutRemovingOtherServices() {
        val panel = RootlessPanel("com.vendor/.Reader", crashed = false)
        assertEquals(Outcome.CLAIMED, panel.repair())
        assertTrue(panel.enabled)
        assertEquals("com.vendor/.Reader:$moved", panel.services)
    }

    @Test fun accessibilityRequiresOurExactComponentAndTheGlobalEnableFlag() {
        for (pkg in listOf(AppIdentity.OWN, AppIdentity.LEGACY)) {
            val full = "$pkg/${AppIdentity.CODE_PACKAGE}.input.PanelAccessibilityService"
            assertTrue(PanelPermissionRepair.accessibilityHeld(pkg, "com.vendor/.Reader:$full", true))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, full, false))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, "$pkg/.OtherService", true))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, "${pkg}.foreign/.Reader", true))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, null, true))
        }
        // The shorthand resolves against the applicationId, so it names the class only for the id that
        // equals the Kotlin package.
        assertTrue(PanelPermissionRepair.accessibilityHeld(AppIdentity.OWN, "${AppIdentity.OWN}/.input.PanelAccessibilityService", true))
        assertFalse(PanelPermissionRepair.accessibilityHeld(AppIdentity.LEGACY, "${AppIdentity.LEGACY}/.input.PanelAccessibilityService", true))
    }
}
