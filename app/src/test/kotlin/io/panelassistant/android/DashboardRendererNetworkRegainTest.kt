package io.panelassistant.android

import android.os.Handler
import io.panelassistant.android.DashboardRendererRig.Companion.allocate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * A blocked admission screen tears the dashboard WebView down, so a returning network finds no page. That
 * used to read as "still waiting for the network at startup" and start admission again: over a sign-in
 * Home Assistant refused, once per Wi-Fi flap, which is the replay the manual-only outcome exists to stop.
 */
class DashboardRendererNetworkRegainTest {
    /** Counts the screens the entity gate schedules, and runs none of them. */
    private var holdScreens = 0
    private val holdScope = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) { holdScreens++ }
    })

    // Entity learning on with nothing learned yet: building the dashboard starts with the entity-learning
    // hold screen, which is how a test sees that the regain started building again.
    private val rig = DashboardRendererRig(
        DashboardRendererRig.SIGNED_IN + mapOf("dashboard_entity_learning" to true, "device_local_setup_ever_completed" to true),
        entityGateScope = holdScope,
    )

    init {
        rig.set("networkRecovery", NetworkRecoveryGate(initiallyAvailable = true).also { it.onLost() })
        DashboardStatusScreens::class.java.getDeclaredField("main").apply { isAccessible = true }
            .set(rig.get("statusScreens"), allocate(Handler::class.java))
    }

    @After fun release() {
        RendererAdmissionRuntime.reset()
        rig.close()
    }

    @Test fun aNetworkRegainOverARefusedSignInLeavesItForTheRetryButton() {
        RendererAdmissionRuntime.record(rig.owner, RendererAdmissionState.BLOCKED, 1L, AdmissionOutcome.CREDENTIAL_REFUSED)

        rig.call("onNetworkAvailable")

        assertEquals("nothing started building the dashboard again", 0, holdScreens)
        assertEquals(AdmissionOutcome.CREDENTIAL_REFUSED, RendererAdmissionRuntime.current()?.record?.outcome)
    }

    /** A block that retries on its own is a different matter: a returning network is its best moment. */
    @Test fun aNetworkRegainOverATransportFailureStillBuildsAgainAtOnce() {
        RendererAdmissionRuntime.record(rig.owner, RendererAdmissionState.BLOCKED, 1L, AdmissionOutcome.TRANSPORT_FAILED)

        rig.call("onNetworkAvailable")

        assertEquals("the dashboard started building again", 1, holdScreens)
    }
}
