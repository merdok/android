package io.panelassistant.android.assist

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnPreDraw
import io.panelassistant.android.CompactNotice
import io.panelassistant.android.Config
import io.panelassistant.android.i18n.CatalogueLoader

/**
 * "Microphone muted", a compact Notice in the bottom-start corner of the dashboard window while Android
 * reports the microphone muted. The network chip holds the opposite corner; like it, this never takes a
 * touch. It has no dismiss: the mute is the owner's own setting and unmuting clears it.
 *
 * A change is announced before it settles, so the owner learns what the small chip means: muting shows
 * the chip large in the middle of the screen, then shrinks it into its corner; unmuting shows
 * "Microphone on" there and fades it. A dashboard that opens already muted just shows the chip.
 */
internal class MicrophoneMutedChip private constructor(
    private val notice: CompactNotice,
    private val mutedText: String,
    private val onText: String,
) {
    private val chip: View get() = notice.view
    private val settle = Runnable {
        chip.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(MOVE_MS).start()
    }
    private val fade = Runnable {
        chip.animate().alpha(0f).setDuration(FADE_MS).withEndAction { rest(muted = false) }.start()
    }

    /** Show [muted]; [announce] when it has just changed rather than being the state found on resume. */
    fun show(muted: Boolean, announce: Boolean) {
        chip.animate().cancel()
        chip.removeCallbacks(settle)
        chip.removeCallbacks(fade)
        if (!announce) {
            rest(muted)
            return
        }
        val text = if (muted) mutedText else onText
        notice.show(text)
        chip.alpha = 1f
        chip.bringToFront()
        // Centre it once the new text has been measured, then hold before settling or fading.
        chip.doOnPreDraw {
            val parent = chip.parent as? View ?: return@doOnPreDraw
            chip.scaleX = PROMINENT_SCALE
            chip.scaleY = PROMINENT_SCALE
            chip.translationX = parent.width / 2f - (chip.left + chip.width / 2f)
            chip.translationY = parent.height / 2f - (chip.top + chip.height / 2f)
            chip.postDelayed(if (muted) settle else fade, HOLD_MS)
        }
        chip.announceForAccessibility(text)
    }

    private fun rest(muted: Boolean) {
        notice.show(mutedText)
        chip.alpha = 1f
        chip.scaleX = 1f
        chip.scaleY = 1f
        chip.translationX = 0f
        chip.translationY = 0f
        if (!muted) notice.hide()
    }

    companion object {
        private const val MARGIN_DP = 12
        private const val PROMINENT_SCALE = 2.5f
        private const val HOLD_MS = 2_500L
        private const val MOVE_MS = 700L
        private const val FADE_MS = 500L

        /** A hidden chip added to [decor]; [VoiceAttention.muteShown] drives it. */
        fun attach(context: Context, decor: ViewGroup): MicrophoneMutedChip {
            // Direct Boot: settings unreadable means the dark dashboard, and so the light chip.
            val config = runCatching { Config(context) }.getOrNull()
            val strings = CatalogueLoader.assets(context)
                .strings(CatalogueLoader.nativeLocale(config?.uiLanguage))
            val notice = CompactNotice(context, config?.dashboardThemeDark ?: true, inline = false, onDismiss = null)
            notice.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val margin = (MARGIN_DP * context.resources.displayMetrics.density).toInt()
            decor.addView(
                notice.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            return MicrophoneMutedChip(notice, strings.get("shell.microphone_muted"), strings.get("shell.microphone_on"))
                .also { notice.view.tag = it }
        }
    }
}
