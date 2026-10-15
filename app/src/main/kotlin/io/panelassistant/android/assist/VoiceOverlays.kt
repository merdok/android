package io.panelassistant.android.assist

import android.animation.ValueAnimator
import android.app.Activity
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.panelassistant.android.Config
import io.panelassistant.android.Notice
import io.panelassistant.android.NoticeForm
import io.panelassistant.android.R

/**
 * The voice assistant's marks over the dashboard: the listening ripple and edge glow, the conversation card,
 * and the microphone mute chip. Each is a child of the decor view, so every content swap keeps it on top;
 * only the card takes a touch, which closes it. [show] attaches them once per window (the card afresh, in
 * the dashboard's current theme) and connects [VoiceAttention] to them while the dashboard is resumed;
 * [hide] disconnects them when it pauses.
 */
internal object VoiceOverlays {
    private const val RIPPLE_TAG = "voice-ripple"
    private const val GLOW_TAG = "voice-glow"
    private const val CARD_TAG = "voice-card"

    fun show(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val ripple = decor.findViewWithTag<WakeRippleView>(RIPPLE_TAG)
            ?: WakeRippleView(activity).also {
                it.tag = RIPPLE_TAG
                decor.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        val glow = decor.findViewWithTag<ListeningGlowView>(GLOW_TAG)
            ?: ListeningGlowView(activity).also {
                it.tag = GLOW_TAG
                it.visibility = View.GONE
                decor.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        // The chip's view carries its controller as its tag.
        val mute = (0 until decor.childCount).firstNotNullOfOrNull { decor.getChildAt(it).tag as? MicrophoneMutedChip }
            ?: MicrophoneMutedChip.attach(activity, decor)
        decor.findViewWithTag<View>(CARD_TAG)?.let(decor::removeView)
        val card = VoiceNotice(activity, decor).also { it.view.tag = CARD_TAG }
        VoiceAttention.ripple = {
            activity.runOnUiThread {
                ripple.bringToFront()
                ripple.setColor(VoiceAttention.color)
                ripple.startRipple()
                card.view.bringToFront()
            }
        }
        VoiceAttention.listening = { active ->
            activity.runOnUiThread {
                glow.bringToFront()
                glow.setColor(VoiceAttention.color)
                glow.setListening(active)
                card.view.bringToFront()
            }
        }
        VoiceAttention.conversation.changed = { activity.runOnUiThread(card::render) }
        VoiceAttention.muteShown = { muted, announce -> activity.runOnUiThread { mute.show(muted, announce) } }
        glow.setColor(VoiceAttention.color)
        glow.setListening(VoiceAttention.attending)
        mute.show(VoiceAttention.muted, announce = false)
        card.render()
    }

    fun hide() {
        VoiceAttention.conversation.changed = null
        VoiceAttention.ripple = null
        VoiceAttention.listening = null
        VoiceAttention.muteShown = null
    }
}

/**
 * [VoiceAttention.conversation] as a Notice at the foot of the dashboard, with the Notice's margin on every
 * side: the phase in the pill, what was heard as the hero, the reply as the line and the three steps as the
 * track. A tap anywhere on it closes it; otherwise it leaves when the conversation says.
 */
private class VoiceNotice(private val activity: Activity, decor: ViewGroup) {
    // Direct Boot: settings unreadable means the dark dashboard, and so the light card.
    private val notice = Notice(activity, runCatching { Config(activity).dashboardThemeDark }.getOrNull() ?: true, NoticeForm.OVERLAY, brand = false)
    private val hero = notice.sentence("")
    // An agent's answer can run long, and the card is there for whoever missed hearing it.
    private val line = notice.line("").apply { maxLines = REPLY_LINES }
    private val track = notice.track(
        listOf(R.string.voice_step_heard, R.string.voice_step_understood, R.string.voice_step_answered).map(activity::getString),
        1,
    )
    private val leave = Runnable { render() }
    val view: View get() = notice.view

    init {
        notice.setBody(hero, line, track.view)
        view.visibility = View.GONE
        view.setOnClickListener { VoiceAttention.conversation.close() }
        val margin = notice.px(Notice.MARGIN)
        decor.addView(view, FrameLayout.LayoutParams(
            notice.widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(margin, margin, margin, margin) })
    }

    fun render() {
        view.removeCallbacks(leave)
        val card = VoiceAttention.conversation.card
        if (card == null) {
            view.visibility = View.GONE
            return
        }
        notice.setPill(activity.getString(when (card.phase) {
            VoiceConversation.Phase.LISTENING -> R.string.voice_pill_listening
            VoiceConversation.Phase.THINKING -> R.string.voice_pill_thinking
            VoiceConversation.Phase.REPLYING -> R.string.voice_pill_replying
            VoiceConversation.Phase.MISSED -> R.string.voice_pill_missed
            VoiceConversation.Phase.FAILED -> R.string.voice_pill_failed
        }), warn = card.failed)
        hero.text = card.heard?.let { activity.getString(R.string.voice_heard, it) }
        hero.visibility = if (card.heard.isNullOrBlank()) View.GONE else View.VISIBLE
        line.text = card.reply
        line.visibility = if (card.reply.isNullOrBlank()) View.GONE else View.VISIBLE
        track.view.visibility = if (card.failed) View.GONE else View.VISIBLE
        track.show(card.current, List(VoiceConversation.STEPS) { if (it < card.done) 1 else 0 }, ValueAnimator.areAnimatorsEnabled())
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.bringToFront()
        }
        card.leaveAtMs?.let { view.postDelayed(leave, (it - SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
    }

    private companion object {
        const val REPLY_LINES = 4
    }
}
