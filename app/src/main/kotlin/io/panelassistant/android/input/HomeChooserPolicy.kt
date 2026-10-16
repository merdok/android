package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef

/** The active window as the chooser answer reads it; [alwaysCentre] is null without an Always button. */
internal data class ChooserWindow(
    val packageName: CharSequence?,
    val title: CharSequence?,
    val alwaysEnabled: Boolean?,
    val alwaysCentre: Pair<Int, Int>?,
)

/**
 * One accessibility connection's answers to the HOME chooser, by [HomeChooserPolicy.next].
 *
 * The app's own start-up can bring the dashboard over the chooser after this service has shown it,
 * either before the chooser is answered or while the tap on Always is on its way, which then lands on
 * the dashboard (a Meta Portal, 2026-10-15). So [VERIFY_MS] after showing or tapping it looks again,
 * and while HOME is still unowned and the chooser is not in front it shows the chooser once more, at
 * most [RETRIES] times per connection. Always is tapped at most [TAPS] times per connection, so a
 * chooser whose Always never takes is not pressed forever.
 */
internal class HomeChooserAnswer(
    private val appName: String,
    private val defaultHome: () -> ActivityRef?,
    private val activeWindow: () -> ChooserWindow?,
    private val showChooser: () -> Boolean,
    private val dismiss: () -> Boolean,
    private val tap: (Int, Int) -> Boolean,
    private val answerAgainIn: (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private var shown = false
    private var acted = false
    private var retries = 0
    private var taps = 0

    fun connected() {
        shown = false
        acted = false
        retries = 0
        taps = 0
    }

    fun answer() {
        val home = defaultHome()
        val window = activeWindow()
        if (acted && home?.pkg == "android" && window?.packageName != "android" && retries < RETRIES) {
            retries++
            shown = false
        }
        acted = false
        val step = HomeChooserPolicy.next(
            home, window?.packageName, window?.title, window?.alwaysEnabled, appName, shown,
        )
        when (step) {
            HomeChooserPolicy.Step.NONE -> Unit
            HomeChooserPolicy.Step.SHOW_CHOOSER -> {
                shown = true
                acted = showChooser()
                log("HOME is unowned; showing the chooser=$acted")
            }
            HomeChooserPolicy.Step.TAP_ALWAYS -> if (taps < TAPS) {
                taps++
                val centre = window?.alwaysCentre
                acted = centre != null && tap(centre.first, centre.second)
                log("HOME chooser offers this app; tapped Always=$acted")
            }
            HomeChooserPolicy.Step.DISMISS -> log("HOME chooser offers another app; dismissed=${dismiss()}")
        }
        if (acted && (retries < RETRIES || taps < TAPS)) answerAgainIn(VERIFY_MS)
    }

    companion object {
        const val VERIFY_MS = 2000L
        const val RETRIES = 2
        const val TAPS = 3
    }
}

/**
 * **Answering Android's HOME chooser.** Updating the app in place makes some firmware (Meta's Portal)
 * forget the preferred HOME, and the next HOME shows "Use Panel Assistant as Home — Just once / Always".
 * A panel without root or the helper cannot set HOME again, so the accessibility service presses Always,
 * which is the setup the owner already chose (maintainer, 2026-10-12).
 *
 * The rule is Panel Assistant's ADB `home_chooser.py`, ported: while HOME is unowned, show the chooser
 * if another window covers it, tap Always only when its title names this app (the app name is not
 * translated, so this matches in any language), and dismiss a chooser it showed itself that offers
 * another app. A chooser that appeared on its own and offers another app is left alone.
 */
internal object HomeChooserPolicy {
    const val ALWAYS_ID = "android:id/button_always"
    const val TITLE_ID = "android:id/title"

    enum class Step { NONE, SHOW_CHOOSER, TAP_ALWAYS, DISMISS }

    /**
     * What to do with the active window. [alwaysEnabled] is null when the window has no Always button.
     * [shown] is whether this connection already pressed HOME to show the chooser: once per connection,
     * because the app's own start-up brings the dashboard over a chooser that appeared during an update.
     */
    fun next(
        home: ActivityRef?,
        windowPackage: CharSequence?,
        title: CharSequence?,
        alwaysEnabled: Boolean?,
        appName: String,
        shown: Boolean,
    ): Step = when {
        // Owned HOME resolves to an app; unowned HOME resolves to the chooser itself, `android/…ResolverActivity`.
        home?.pkg != "android" -> Step.NONE
        windowPackage != "android" -> if (shown) Step.NONE else Step.SHOW_CHOOSER
        alwaysEnabled == null -> Step.NONE
        alwaysEnabled && title?.contains(appName) == true -> Step.TAP_ALWAYS
        shown -> Step.DISMISS
        else -> Step.NONE
    }

    /**
     * Android keeps an accessibility service's capabilities from when its connection was made, so the
     * update that adds window-content retrieval only gets it at the next bind (a reboot, or a
     * re-enable). Returns the enabled-services value without this service when it should re-enable
     * itself once to pick the capability up, or null when it already has it or is not listed.
     */
    fun rebindEntries(enabled: String?, pkg: String, serviceClass: String, capabilities: Int): String? {
        if (enabled == null || capabilities and CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0) return null
        val entries = enabled.split(':').filter { it.isNotBlank() }
        val others = entries.filterNot { entry ->
            val cls = entry.substringAfter('/', "")
            entry.substringBefore('/') == pkg && (if (cls.startsWith('.')) pkg + cls else cls) == serviceClass
        }
        return if (others.size < entries.size) others.joinToString(":") else null
    }

    /** `AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`. */
    const val CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT = 1
}
