package io.panelassistant.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused refresh token is remembered so that nothing replays it to Home Assistant on its own: every
 * caller gets the same refusal until the credential changes or a person asks again. The Retry button on
 * a blocked admission screen is that person asking; the automatic admission timer is not.
 */
class DashboardRendererAdmissionRetryTest {
    private val ha = LoopbackHa()

    // Entity learning on and setup done: admission stops at the entity-bootstrap hold, whose screen runs on
    // a scope that never starts, so a retry reaches no status screen the JVM cannot draw.
    private val rig = DashboardRendererRig(
        prefs = mapOf(
            "ha_url" to ha.url,
            "ha_token" to "expired",
            "ha_token_expiry" to System.currentTimeMillis() / 1000 - 10,
            "ha_refresh_token" to "refused-refresh",
            "ha_client_id" to "client",
            "dashboard_entity_learning" to true,
            "device_local_setup_ever_completed" to true,
        ),
        entityGateScope = CoroutineScope(Job().apply { cancel() }),
    )

    @After fun release() {
        HaCredentialManager.forgetRefusal()
        rig.close()
        ha.close()
    }

    @Test fun aRetryPressAsksHomeAssistantAgainAboutARefusedLoginButTheAutomaticRetryDoesNot() {
        ha.answer(400, """{"error":"invalid_grant"}""")
        assertTrue(HaCredentialManager.resolve(rig.config).rejected)
        assertEquals(1, ha.requests.get())

        // The owner fixes the account in Home Assistant; the next answer would be a fresh token.
        ha.answer(200, """{"access_token":"after","expires_in":1800,"token_type":"Bearer"}""")

        rig.call("retryAdmission", false)
        assertTrue("the automatic retry leaves the refusal standing", HaCredentialManager.resolve(rig.config).rejected)
        assertEquals("the automatic retry did not ask Home Assistant", 1, ha.requests.get())

        rig.call("retryAdmission", true)
        assertEquals("after", HaCredentialManager.resolve(rig.config).session?.accessToken)
        assertEquals("the press asked Home Assistant once more", 2, ha.requests.get())
    }
}
