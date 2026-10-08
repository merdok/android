package io.panelassistant.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind the full-screen Notice host: the one theme rule every status screen takes,
 * and when a built frame is kept for the next phase. Sizes and colours are the Notice's; see [NoticeTest].
 */
class StatusSurfaceSpecTest {

    /** A configured dashboard theme is the panel's decision and outranks both fallbacks. */
    @Test
    fun aConfiguredThemeWinsOverTheSystemAndTheStoredPreference() {
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = true, systemDark = false, storedDark = false, sdkInt = 33))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = false, systemDark = true, storedDark = true, sdkInt = 33))
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = true, systemDark = false, storedDark = false, sdkInt = 26))
    }

    /** Android 10 and newer have a real system night setting; older panels do not, so the panel's own
     *  stored preference is the only honest answer there. */
    @Test
    fun theSystemSettingIsFollowedOnlyWhereAndroidActuallyHasOne() {
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = true, storedDark = false, sdkInt = 29))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = false, storedDark = true, sdkInt = 29))
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = false, storedDark = true, sdkInt = 28))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = true, storedDark = false, sdkInt = 28))
    }

    /**
     * An explicit Dark/Light policy outranks every other input, including a theme already observed
     * from Home Assistant. These screens stand in for the dashboard, so a panel told to show a dark
     * dashboard must not show a light screen on the way to it — and it must be right on the FIRST
     * frame after the change, not one renderer observation later.
     */
    @Test
    fun anExplicitThemePolicyOutranksEveryOtherInput() {
        // Against a contrary observed theme, both directions, on both Android tiers.
        assertTrue(statusSurfaceDark(forcedDark = true, configuredDark = false, systemDark = false, storedDark = false, sdkInt = 33))
        assertTrue(!statusSurfaceDark(forcedDark = false, configuredDark = true, systemDark = true, storedDark = true, sdkInt = 33))
        assertTrue(statusSurfaceDark(forcedDark = true, configuredDark = false, systemDark = false, storedDark = false, sdkInt = 26))
        assertTrue(!statusSurfaceDark(forcedDark = false, configuredDark = true, systemDark = true, storedDark = true, sdkInt = 26))
        // Follow surrenders the decision entirely: it must not act as a third opinion.
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = true, systemDark = false, storedDark = false, sdkInt = 33))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = false, systemDark = true, storedDark = true, sdkInt = 33))
    }

    // --- keeping the frame between phases ---------------------------------------------------------

    private val square = StatusSurfaceGeometry(480, 480, 1f)

    /** A frame is kept for the next phase, and only a theme or geometry change earns a rebuild. */
    @Test
    fun theFrameIsKeptUnlessTheThemeChanges() {
        assertTrue("the same theme must reuse the frame", statusSurfaceReusable(square, true, square, true))
        assertTrue("the same theme must reuse the frame", statusSurfaceReusable(square, false, square, false))
        assertTrue("a theme change must rebuild — the artwork and every colour differ", !statusSurfaceReusable(square, true, square, false))
        assertTrue("there is nothing to reuse before the first frame exists", !statusSurfaceReusable(null, null, square, true))
    }

    /**
     * A frame built for one panel shape is not reused on another. The dashboard declares
     * `orientation|screenSize` in its `configChanges`, so Android hands it a new size without recreating
     * it, and a kept frame would keep the old card width and type scale.
     */
    @Test
    fun theFrameIsRebuiltWhenThePanelGeometryChanges() {
        val portrait = StatusSurfaceGeometry(480, 800, 1f)
        val landscape = StatusSurfaceGeometry(800, 480, 1f)
        assertTrue("a rotation must rebuild the frame", !statusSurfaceReusable(portrait, true, landscape, true))
        assertTrue("a density change must rebuild the frame", !statusSurfaceReusable(square, true, square.copy(density = 2f), true))
        assertTrue("an unchanged panel must still reuse it", statusSurfaceReusable(landscape, true, StatusSurfaceGeometry(800, 480, 1f), true))
    }
}
