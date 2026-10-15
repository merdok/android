package io.panelassistant.android.http

import io.panelassistant.android.sensors.HaLifecycle
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
