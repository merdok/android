package io.panelassistant.android

import io.panelassistant.android.util.DashboardTheme
import android.app.Activity
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView

/**
 * The Panel Assistant lock-up on the panel's own background, for a screen that is not a [Notice] (the
 * admin launcher). Light name on a dark background, dark name on a light one.
 */
internal fun statusBrandMark(activity: Activity, dark: Boolean): ImageView = ImageView(activity).apply {
    setImageDrawable(brandWordmark(activity, night = dark))
    adjustViewBounds = true
    scaleType = ImageView.ScaleType.FIT_CENTER
    contentDescription = activity.getString(R.string.wordmark_description)
}

/**
 * True when this frame is already the installed content view.
 *
 * `setContentView` removes every child of the content parent before adding the new one, so handing it
 * the same root again detaches and reattaches the whole hierarchy — including the brand row this frame
 * exists to hold still. Keeping the instance is not enough on its own; the install has to be skipped too.
 */
internal fun statusSurfaceAlreadyInstalled(currentRoot: View?, surfaceRoot: View): Boolean =
    currentRoot === surfaceRoot && surfaceRoot.parent != null

/**
 * The full-screen form of the [Notice]: every screen Panel Assistant shows in place of the dashboard is
 * one Notice card centred on the panel's own background.
 *
 * The card sits at the top of the panel, so the brand row never moves when a phase changes height.
 *
 * A wall panel has no title bar, no address bar and no window chrome, so a screen that opens with a
 * sentence about Home Assistant reads as Home Assistant's — including when what it reports is Panel
 * Assistant's own decision to stop, wait or hold. The card's brand row names who is speaking.
 *
 * A screen keeps ONE instance and calls [setBody] on each phase: the brand row is never rebuilt, so the
 * mark and the version are not torn down and redrawn on a screen whose only change was a line of text.
 * The card scrolls rather than clips, so an unforeseen string, a large font scale or a small panel never
 * puts an action off the screen.
 */
internal class StatusSurface(
    activity: Activity,
    /** Resolved by [statusSurfaceDark] — do not re-derive a theme per screen. */
    val dark: Boolean,
) {
    val geometry: StatusSurfaceGeometry = geometryFor(activity)
    val notice: Notice = Notice(activity, backdropDark = dark, form = NoticeForm.FULL_SCREEN)

    /** The scroll containment, so a phase taller than the panel scrolls rather than clips. */
    val scroll: ScrollView = ScrollView(activity).apply {
        isFillViewport = true
        addView(
            FrameLayout(activity).apply {
                val margin = notice.px(Notice.MARGIN)
                setPadding(0, margin, 0, margin)
                // Top, not centred: a centred card moved its brand row every time a phase changed height,
                // and the mark holding still across phases is what this frame is for.
                addView(notice.view, FrameLayout.LayoutParams(notice.widthPx, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    /** The view to hand to `setContentView`. */
    val root: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(android.graphics.Color.parseColor(statusPalette(dark).background))
        addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /**
     * Replace the slots for a new phase. A new phase is different content, not a continuation, so it
     * starts at the top: keeping the previous offset dropped a reader into the middle of a replacement.
     */
    fun setBody(pill: CharSequence, vararg rows: View?) {
        scroll.scrollTo(0, 0)
        notice.setPill(pill)
        notice.setBody(*rows)
    }

    /** Append the panel's running state to the version under the name. */
    fun setBrandCaption(suffix: String) = notice.setCaption(suffix)

    companion object {
        fun geometryFor(activity: Activity): StatusSurfaceGeometry {
            val dm = activity.resources.displayMetrics
            return StatusSurfaceGeometry(dm.widthPixels, dm.heightPixels, dm.density)
        }

        /** The single theme decision, so no screen re-derives one of its own. */
        fun darkFor(activity: Activity, config: Config): Boolean = statusSurfaceDark(
            // Resolved, never the raw package: blank means Auto, which is the built-in renderer too.
            // Effective, so an Ambient panel's native screens follow the room exactly as its dashboard does.
            forcedDark = DashboardTheme.forcedDark(config.dashboardThemeEffective).takeIf {
                RendererResolver.isBuiltinSelection(config.dashboardPackage, activity.packageName)
            },
            configuredDark = config.dashboardThemeDark,
            systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
            storedDark = config.darkMode,
            sdkInt = android.os.Build.VERSION.SDK_INT,
        )
    }
}
