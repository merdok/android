package io.panelassistant.android.config

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic pin: the address family setting governs the panel's Home Assistant WebSocket connections,
 * and the user-visible label/help must say so. This pins the semantic tokens, not sentence wording.
 */
class AddressFamilyScopeSpecTest {
    @Test fun addressFamilySettingNamesTheConnectionsItGoverns() {
        val spec = checkNotNull(SettingsRegistry.spec(SettingsRegistry.ADDRESS_FAMILY_KEY))
        val surface = spec.label + " " + spec.help
        assertTrue("names Home Assistant: $surface", surface.contains("Home Assistant"))
        // The qualifier is part of the pinned semantic: the policy governs WebSocket connections,
        // and claiming plain Home Assistant "connections" overstates it — REST reads iterate
        // addresses natively and do not consult this setting.
        assertTrue("names the WebSocket qualifier: $surface", surface.contains("WebSocket"))
    }
}
