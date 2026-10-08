package io.panelassistant.android

/**
 * The pure decisions behind [StatusSurface], the full-screen form of the [Notice]: which theme it takes
 * and when a built frame can be kept. Every size comes from the Notice and [PanelTokens.scale]; every
 * colour from [PanelTokens]. Imports nothing, so the unit gate can assert it directly.
 */

/** The display a frame was laid out for. */
internal data class StatusSurfaceGeometry(val widthPx: Int, val heightPx: Int, val density: Float)

/** Colours of the panel's own background and the screens that are not a Notice; see [PanelTokens.Status]. */
internal fun statusPalette(dark: Boolean): StatusPalette =
    if (dark) PanelTokens.Status.DARK else PanelTokens.Status.LIGHT

/**
 * The one dark/light rule for every status screen.
 *
 * An explicit dashboard theme policy wins outright, because these screens exist to stand in for the
 * dashboard: a panel told to show a dark dashboard must not flash a light screen on the way to it.
 * [forcedDark] is null unless the built-in renderer is the resolved dashboard and the policy is
 * Dark or Light, so a panel rendering through a Companion is unaffected by a setting that cannot
 * reach its dashboard. Deciding from the policy rather than waiting for the renderer to observe its
 * effect is what makes the very first frame after a policy change correct rather than one page load
 * late.
 *
 * Below that, the theme observed from Home Assistant wins where it is set, because a wall panel's
 * look is a deliberate choice rather than a system preference. Where it is unset, Android 10 and
 * newer have a real system setting to follow; below that the platform has no reliable night mode, so
 * the panel's own stored preference is the only honest answer.
 *
 * Screens previously disagreed here — some read the system uiMode, some defaulted to dark — so the
 * same panel could show a light startup screen and a dark failure screen minutes apart.
 */
internal fun statusSurfaceDark(
    forcedDark: Boolean?,
    configuredDark: Boolean?,
    systemDark: Boolean,
    storedDark: Boolean,
    sdkInt: Int,
): Boolean = forcedDark ?: configuredDark ?: if (sdkInt >= 29) systemDark else storedDark

/**
 * Whether an already-built frame can be kept for the next phase.
 *
 * Keeping it is what holds the brand row still: a rebuilt frame re-inflates it, so the mark is torn down
 * and redrawn on a screen whose only actual change was a line of text.
 *
 * Two things force a rebuild, and both are easy to forget. A theme change, because the artwork and
 * every colour differ. And a **geometry** change: the dashboard declares `orientation|screenSize` in
 * its `configChanges`, so Android hands it a new size without recreating it, and a frame built for
 * the old size would keep the old card width and type scale on a panel that is no longer that shape.
 */
internal fun statusSurfaceReusable(
    cachedGeometry: StatusSurfaceGeometry?,
    cachedDark: Boolean?,
    geometry: StatusSurfaceGeometry,
    dark: Boolean,
): Boolean = cachedGeometry != null && cachedDark != null && cachedGeometry == geometry && cachedDark == dark
