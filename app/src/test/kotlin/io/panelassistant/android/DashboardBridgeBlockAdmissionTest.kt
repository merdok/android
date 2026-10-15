package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bridge-phase block (the secure listener cannot attach, or the frontend handshake was missed)
 * replaces the admission verdict with BLOCKED, so the compatibility pass that admitted the URL must stop
 * standing: a relaunch reuses a standing admission instead of re-admitting, and status then keeps naming
 * the block over a dashboard that rendered. `showDashboardBlocked` is the one route that withdraws it, so
 * every bridge-phase block must go through it.
 *
 * Source-text reason: the behaviour is unreachable on the JVM: each bridge-phase site passes
 * `getString(...)`, which the mockable android.jar answers with null, and Kotlin's call-site non-null
 * check throws before the block route is entered, on the base and on the fix alike.
 */
class DashboardBridgeBlockAdmissionTest {
    @Test fun everyBridgePhaseBlockWithdrawsTheStandingAdmission() {
        val file = File("src/main/kotlin/io/panelassistant/android/DashboardActivity.kt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val source = file.readText()
        val outcomes = Regex("""AdmissionOutcome\.(BRIDGE_ATTACH_FAILED|BRIDGE_HANDSHAKE_MISSED)\b""").findAll(source).toList()
        assertTrue("no bridge-phase block found", outcomes.isNotEmpty())
        val direct = outcomes.mapNotNull { match ->
            val before = source.substring(0, match.range.first)
            val routed = before.lastIndexOf("showDashboardBlocked(")
            val bypass = before.lastIndexOf("statusScreens.showBlockedAdmissionScreen(")
            if (bypass > routed) "line ${before.count { it == '\n' } + 1}: ${match.value}" else null
        }
        assertEquals("bridge-phase blocks drawn without withdrawing the admission", emptyList<String>(), direct)
    }
}
