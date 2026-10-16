package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconcile's decision: what the live renderer embodies against what the configuration wants. The
 * activity rebuilds once for the causes listed and resets what their union needs.
 */
class RendererRebuildCauseTest {
    private val owner = HaAuthOwner(url = "http://ha.local:8123", refreshToken = "refresh", clientId = "client", staticAccessToken = "")
    private val home = HomeDashboardResolutionOwner(owner, "/wall")
    private val wanted = RendererConfiguration("http://ha.local:8123", home, owner, "disabled:learning=false", "Follow Home Assistant")

    private fun causes(embodied: RendererConfiguration, wanted: RendererConfiguration = this.wanted, route: Boolean = false) =
        rendererRebuildCauses(routeChanged = route, signedIn = false, embodied = embodied, wanted = wanted)

    @Test fun aRendererThatEmbodiesTheConfigurationNeedsNothing() {
        assertEquals(emptyList<RendererRebuildCause>(), causes(wanted))
    }

    /**
     * Before the first dashboard WebView, nothing has baked a theme or a filter yet: the build to come bakes
     * the configured ones. Comparing against a placeholder announced a theme restart on a cold start.
     */
    @Test fun withNoDashboardWebViewAThemeOrFilterDifferenceIsNoCause() {
        val coldStart = wanted.copy(authOwner = null, filter = null, theme = null)

        assertEquals(emptyList<RendererRebuildCause>(), causes(coldStart, wanted.copy(theme = "Dark", filter = "enabled:abc")))
    }

    /**
     * A credential change is consent to ask Home Assistant again, even when an earlier route change had
     * already cleared the home dashboard's resolution, so the home owner can no longer show the change.
     */
    @Test fun aChangedCredentialIsAConsentingCauseEvenWithNoHomeResolution() {
        val refused = wanted.copy(homeOwner = null)
        val fixed = owner.copy(refreshToken = "new-refresh")

        val found = causes(refused, wanted.copy(authOwner = fixed, homeOwner = home.copy(authOwner = fixed)), route = true)

        assertEquals(listOf(RendererRebuildCause.ROUTE, RendererRebuildCause.CREDENTIAL), found)
        assertTrue("one rebuild re-admits, resolves home and counts as consent",
            found.any { it.readmit } && found.any { it.resolveHome } && found.any { it.consent })
    }

    /** An access token that its refresh token rotates is not a different credential. */
    @Test fun aRotatedAccessTokenIsNoCause() {
        val rotated = HaAuthSnapshot("http://ha.local:8123/", "new-access", "refresh", 99L, "client").stableOwner()

        assertEquals(emptyList<RendererRebuildCause>(), causes(wanted, wanted.copy(authOwner = rotated)))
    }
}
