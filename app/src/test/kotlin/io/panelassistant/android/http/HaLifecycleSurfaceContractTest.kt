package io.panelassistant.android.http

import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleCoordinator
import io.panelassistant.android.sensors.HaLifecycleNotice
import io.panelassistant.android.sensors.HaLifecyclePhase
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleState
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `/health` lifecycle token and the OpenAPI description that publishes it. */
class HaLifecycleSurfaceContractTest {
    private fun snapshot(state: HaLifecycleState) =
        HaLifecycle.Snapshot(state, refused = false, revision = 1L, backOnlineRemainingMs = 0L)

    @Test fun theHealthTokenIsAbsentWhenThePanelIsNotWatchingOrUnowned() {
        // The token must not degrade to a default value: an absent token means "nothing to say", which is
        // what keeps the /health line byte-identical for every existing consumer.
        assertEquals("", haLifecycleHealthToken(false, snapshot(HaLifecycleState.SHUTTING_DOWN)))
        assertEquals("", haLifecycleHealthToken(true, null))
    }

    @Test fun theTokenCarriesTheStateWithNoSourceToken() {
        assertEquals(
            " ha=shutting_down ha_reason=unknown ha_grace_ms=0",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.SHUTTING_DOWN)),
        )
        assertEquals(
            " ha=connection_lost ha_reason=unknown ha_grace_ms=0",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.CONNECTION_LOST)),
        )
    }

    /** The refusal explains the idle row, so it rides the same observation instead of a second read. */
    @Test fun theRefusalRidesTheSameObservation() {
        assertEquals(
            " ha=normal ha_refused=1 ha_reason=unknown ha_grace_ms=0",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.NORMAL).copy(refused = true)),
        )
    }

    /**
     * A finished restart leaves nothing behind. A Meta Portal reported `ha=normal ha_reason=restart`
     * with an elapsed time of 3.5 hours because the decay from back-online kept the restart notice.
     */
    @Test fun aFinishedRestartLeavesNoReasonOnTheHealthLine() {
        var now = 0L
        val ha = HaLifecycleCoordinator(HaLifecycle(backOnlineWindowMs = 8_000L), nowMs = { now })
        ha.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.RESTART, 0L, 120_000L))
        now = 60_000L
        ha.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.READY, HaLifecycleReason.RESTART, null, null))
        assertEquals(
            "the recovery notice still says how long the restart took",
            " ha=back_online ha_reason=restart ha_elapsed_ms=60000 ha_expected_ms=120000 ha_grace_ms=0",
            haLifecycleHealthToken(true, ha.snapshot()),
        )

        now = 60_000L + 8_000L + 3 * 3_600_000L
        assertEquals(" ha=normal ha_reason=unknown ha_grace_ms=0", haLifecycleHealthToken(true, ha.snapshot()))
    }

    @Test fun openApiHealthTokenContractNamesEveryLifecycleState() {
        // Source-text reason: openapi.json is the published API schema contract.
        val document = JSONObject(TestSources.asset("openapi.json").readText())
        val description = document
            .getJSONObject("paths").getJSONObject("/api/v1/health")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
            .getString("description")
        HaLifecycleState.entries.forEach { state ->
            assertTrue(
                "OpenAPI must name the ${state.wireValue} state the token can carry",
                description.contains(state.wireValue),
            )
        }
        assertTrue("and must say the token can be absent", description.contains("absent"))
    }
}
