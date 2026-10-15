package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef

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
 * A tap that Android accepted can still miss: the app's own start-up can bring the dashboard over the
 * chooser while the tap is on its way, and the tap then lands on the dashboard (a Meta Portal,
 * 2026-10-15). So [VERIFY_MS] after a tap it looks again, and while HOME is still unowned it shows the
 * chooser once more. Once per connection, so a chooser whose Always never takes is not pressed forever.
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
    private var tapped = false
    private var retried = false

    fun connected() {
        shown = false
        tapped = false
        retried = false
    }

    fun answer() {
        val home = defaultHome()
        if (tapped && !retried && home?.pkg == "android") {
            retried = true
            shown = false
        }
        tapped = false
        val window = activeWindow()
        val step = HomeChooserPolicy.next(
            home, window?.packageName, window?.title, window?.alwaysEnabled, appName, shown,
        )
        when (step) {
            HomeChooserPolicy.Step.NONE -> Unit
            HomeChooserPolicy.Step.SHOW_CHOOSER -> {
                shown = true
                log("HOME is unowned; showing the chooser=${showChooser()}")
            }
            HomeChooserPolicy.Step.TAP_ALWAYS -> {
                val centre = window?.alwaysCentre
                tapped = centre != null && tap(centre.first, centre.second)
                log("HOME chooser offers this app; tapped Always=$tapped")
                if (tapped && !retried) answerAgainIn(VERIFY_MS)
            }
            HomeChooserPolicy.Step.DISMISS -> log("HOME chooser offers another app; dismissed=${dismiss()}")
        }
    }

    companion object {
        const val VERIFY_MS = 2000L
    }
}

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
