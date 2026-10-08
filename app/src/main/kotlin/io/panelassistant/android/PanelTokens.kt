// Generated from tools/tokens/tokens.json by tools/tokens/tokens.py; do not edit.
package io.panelassistant.android

/**
 * The one source of every colour a native panel screen draws, and of the scale rule that sizes
 * them. Imports nothing, so the unit gate can read it directly.
 */
internal object PanelTokens {
    /** The shortest edge, in pixels at density 1, that every baseline size is drawn for. */
    const val BASELINE_EDGE_PX = 480.0f

    /**
     * How much to scale a baseline size: grows with a small display, but never past the display's
     * logical density, because a wall panel is read from across a room and a notice must not keep
     * taking a larger share of a bigger screen (on a 1920x1200 panel an uncapped notice took about
     * 40% of the height). Capping at the density honours a logical-density override. A non-positive
     * density counts as 1, because collapsing a notice to nothing is worse than an oversized one.
     */
    fun scale(shortestEdgePx: Float, density: Float): Float =
        minOf(shortestEdgePx / BASELINE_EDGE_PX, if (density > 0f) density else 1f)

    /** [value] dp in whole pixels at [density], truncated. */
    fun dp(value: Int, density: Float): Int = (value * density).toInt()

    /**
     * The panel's own background behind a full-screen Notice and the proximity wizard, and the
     * admin launcher's text and button. Hex strings, because the dashboard's blank document puts
     * the background into HTML.
     */
    object Status {
        val DARK = StatusPalette(
            background = "#111111",
            body = "#c8ccd2",
            subtle = "#8a8f99",
            accent = "#4a9eff",
            actionBackground = "#2f6fd0",
        )
        val LIGHT = StatusPalette(
            background = "#ffffff",
            body = "#2a2e34",
            subtle = "#5a6068",
            accent = "#1669d6",
            actionBackground = "#2557a7",
        )
    }

    /**
     * The admin launcher's tile grid. Its tiles are drawn in the status background, so the page
     * behind them is a shade off it or the tiles vanish.
     */
    object Launcher {
        val DARK = LauncherColours(
            page = "#111111",
            tile = "#1d1f24",
        )
        val LIGHT = LauncherColours(
            page = "#f3f4f6",
            tile = "#ffffff",
        )
    }

    /**
     * The Home Assistant restart notice (HaLifecycleBar). DARK is the dark card, shown over a light
     * dashboard, and LIGHT the light card over a dark one. Every text role keeps WCAG AA 4.5:1 or
     * better over a white, grey or black dashboard at the card's opacity (worst 5.4:1, secondary
     * labels on the dark card); the accent marks only the dot and the track, which need 3:1 and get
     * 5.1:1 or better. Recheck those figures before changing a colour.
     */
    object Notice {
        val DARK = NoticeColours(
            surface = 0xFF16181C.toInt(),
            text = 0xFFF3F5F7.toInt(),
            secondary = 0xFFC3CAD1.toInt(),
            muted = 0xFFA9B2BC.toInt(),
            accent = 0xFF4FC3F7.toInt(),
        )
        val LIGHT = NoticeColours(
            surface = 0xFFF7F8FA.toInt(),
            text = 0xFF15181C.toInt(),
            secondary = 0xFF3D4650.toInt(),
            muted = 0xFF46505B.toInt(),
            accent = 0xFF01579B.toInt(),
        )
    }

    /**
     * The migration notice's amber, opaque because the surface behind is an arbitrary dashboard.
     * The chips themselves are compact Notices.
     */
    object Chip {
        val DARK = ChipColours(
            warningSurface = 0xFF2A2418.toInt(),
            warningBorder = 0xFFD2951F.toInt(),
            warningLabel = 0xFFFAF2E2.toInt(),
        )
        val LIGHT = ChipColours(
            warningSurface = 0xFFFDF6E7.toInt(),
            warningBorder = 0xFFA5741A.toInt(),
            warningLabel = 0xFF3A2A08.toInt(),
        )
    }

    /**
     * The proximity wizard's pictogram, countdown ring and wave cue. The wizard's text, buttons and
     * background are the shared Notice and status colours.
     */
    object Proximity {
        const val WAVE = 0xFFC8ADFF.toInt()
        const val WAVE_TEXT = 0xFFE3D5FF.toInt()
        const val PICTOGRAM_FLOOR = 0xFF405671.toInt()
        const val PICTOGRAM_PANEL = 0xFF172B43.toInt()
        const val PICTOGRAM_PANEL_LINE = 0xFF487CB8.toInt()
        const val PICTOGRAM_HAND = 0xFFC5DEFE.toInt()
        const val RING_ACCENT = 0xFF71ADFF.toInt()
        const val RING_TRACK = 0xFF273B54.toInt()
    }

    /**
     * Views drawn over the dashboard that are not cards: the voice attention glow and ripple, the
     * camera indicator, the navigation bar and the dashboard's own dark backdrop.
     */
    object Overlay {
        /** The voice glow and wake ripple, unless Home Assistant names another. */
        const val VOICE_ATTENTION = 0xFF00FF88.toInt()
        const val CAMERA_RECORDING = 0xFFDC1E1E.toInt()
        /** Charcoal at about 76%: translucent but still reads solid. */
        const val NAVBAR_BAR = 0xC2282C34.toInt()
        /** Press-feedback flash, about 33% white. */
        const val NAVBAR_PRESS = 0x55FFFFFF.toInt()
        /** 60% white group divider. */
        const val NAVBAR_SEPARATOR = 0x99FFFFFF.toInt()
        /** The slider's unfilled track, about 33% white. */
        const val NAVBAR_SLIDER_TRACK = 0x55FFFFFF.toInt()
        const val DASHBOARD_DARK = 0xFF121212.toInt()
    }

    /**
     * Colours Kotlin writes as text rather than through a stylesheet: the bare `:8888` result pages
     * that load no CSS, and the capability colours in the status JSON Panel Assistant reads.
     */
    object Page {
        const val BACKGROUND = "#111"
        const val TEXT = "#eee"
        const val LINK = "#9cf"
        const val CAPABILITY_OK = "#48c774"
        const val CAPABILITY_DEGRADED = "#d9a528"
        const val CAPABILITY_NONE = "#d04a3b"
        const val CAPABILITY_UNKNOWN = "#888"
    }
}

/**
 * The panel's own background behind a full-screen Notice and the proximity wizard, and the admin
 * launcher's text and button. Hex strings, because the dashboard's blank document puts the
 * background into HTML.
 */
internal data class StatusPalette(
    val background: String,
    val body: String,
    val subtle: String,
    val accent: String,
    /**
     * The emphasised action. Dark: 3.87:1 against the page and 4.88:1 for its label; the earlier
     * #2557a7 reached only 2.70:1 against the dark page.
     */
    val actionBackground: String,
)

/**
 * The admin launcher's tile grid. Its tiles are drawn in the status background, so the page behind
 * them is a shade off it or the tiles vanish.
 */
internal data class LauncherColours(
    val page: String,
    val tile: String,
)

/**
 * The Home Assistant restart notice (HaLifecycleBar). DARK is the dark card, shown over a light
 * dashboard, and LIGHT the light card over a dark one. Every text role keeps WCAG AA 4.5:1 or
 * better over a white, grey or black dashboard at the card's opacity (worst 5.4:1, secondary labels
 * on the dark card); the accent marks only the dot and the track, which need 3:1 and get 5.1:1 or
 * better. Recheck those figures before changing a colour.
 */
internal data class NoticeColours(
    val surface: Int,
    val text: Int,
    val secondary: Int,
    val muted: Int,
    val accent: Int,
)

/**
 * The migration notice's amber, opaque because the surface behind is an arbitrary dashboard. The
 * chips themselves are compact Notices.
 */
internal data class ChipColours(
    val warningSurface: Int,
    val warningBorder: Int,
    val warningLabel: Int,
)
