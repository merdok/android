package io.panelassistant.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.panelassistant.android.control.BuiltinDashboard
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The built-in renderer's keep-screen-on, applied to its window and to the views inside it.
 *
 * `Window.clearFlags(FLAG_KEEP_SCREEN_ON)` alone does not let Android time out: `ViewRootImpl` forces
 * the window flag back on while any attached view has `keepScreenOn` set, and the dashboard WebView
 * sets one on a child view of its own while it plays media. So while a screen-timeout off has
 * [BuiltinDashboard.keepScreenOnReleased] set, this also clears every view-level hold under [root]
 * and remembers which views it cleared, and gives exactly those back (where still attached) once the
 * release ends.
 *
 * Generic over the view type so the walk is testable without Android; [DashboardActivity] binds it to
 * its real window and view tree. [apply] runs on the thread that owns the views; [attach] registers it
 * with [BuiltinDashboard] through [post], which must deliver to that thread.
 */
internal class RendererScreenHold<V : Any>(
    private val preventIdleDim: () -> Boolean,
    private val setWindowKeepScreenOn: (Boolean) -> Unit,
    private val root: () -> V?,
    private val children: (V) -> List<V>,
    private val viewKeepsScreenOn: (V) -> Boolean,
    private val setViewKeepScreenOn: (V, Boolean) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val suppressed: MutableSet<V> = Collections.newSetFromMap(IdentityHashMap())

    private var listener: (() -> Unit)? = null

    // Last value given to the window: [apply] runs on every poll tick of a screen-timeout wait, and
    // each Window flag write is a relayout round trip even when nothing changes.
    private var windowKeepScreenOn: Boolean? = null

    fun apply() {
        val released = BuiltinDashboard.keepScreenOnReleased
        val keep = shouldKeepBuiltInRendererScreenOn(preventIdleDim(), released)
        if (keep != windowKeepScreenOn) {
            setWindowKeepScreenOn(keep)
            windowKeepScreenOn = keep
        }
        if (released) {
            var cleared = 0
            forEachView { view ->
                if (viewKeepsScreenOn(view)) {
                    setViewKeepScreenOn(view, false)
                    suppressed += view
                    cleared++
                }
            }
            if (cleared > 0) log("released $cleared view-level keep-screen-on hold(s) for a screen-timeout off")
        } else if (suppressed.isNotEmpty()) {
            // Only views still in the tree: a hold the WebView dropped meanwhile left with its view.
            forEachView { view -> if (suppressed.remove(view)) setViewKeepScreenOn(view, true) }
            suppressed.clear()
        }
    }

    fun attach(post: (() -> Unit) -> Unit) {
        detach()
        val l: () -> Unit = { post(::apply) }
        listener = l
        BuiltinDashboard.setKeepScreenOnListener(l)
    }

    fun detach() {
        listener?.let(BuiltinDashboard::clearKeepScreenOnListener)
        listener = null
    }

    private fun forEachView(action: (V) -> Unit) {
        val start = root() ?: return
        val pending = ArrayDeque<V>().apply { add(start) }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            action(view)
            pending.addAll(children(view))
        }
    }
}

internal fun shouldKeepBuiltInRendererScreenOn(
    preventIdleDim: Boolean,
    keepScreenOnReleased: Boolean = BuiltinDashboard.keepScreenOnReleased,
): Boolean = preventIdleDim && !keepScreenOnReleased

/** [RendererScreenHold] bound to [activity]'s real window and decor-view tree. */
internal fun windowRendererScreenHold(
    activity: Activity,
    preventIdleDim: () -> Boolean,
    log: (String) -> Unit,
): RendererScreenHold<View> = RendererScreenHold(
    preventIdleDim = preventIdleDim,
    setWindowKeepScreenOn = { keep ->
        if (keep) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    },
    root = { activity.window?.peekDecorView() },
    children = { view -> if (view is ViewGroup) List(view.childCount) { view.getChildAt(it) } else emptyList() },
    viewKeepsScreenOn = { it.keepScreenOn },
    setViewKeepScreenOn = { view, keep -> view.keepScreenOn = keep },
    log = log,
)
