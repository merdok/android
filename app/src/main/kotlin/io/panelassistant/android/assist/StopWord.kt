package io.panelassistant.android.assist

import android.content.Context
import io.panelassistant.android.assist.wakeword.LoadedWakeWordModel
import io.panelassistant.android.assist.wakeword.MicroWakeWordModelConfig
import io.panelassistant.android.assist.wakeword.NativeMicroWakeWord
import io.panelassistant.android.assist.wakeword.WakeWordDetector
import io.panelassistant.android.assist.wakeword.WakeWordHit
import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Hears "stop" while the panel is answering, and ends the run: a reply, an announcement or a ringing timer.
 *
 * It hears the microphone from the start of a run, so its frontend has settled by the time it matters, but
 * acts only while the panel is thinking or speaking, never while it listens for a command, so the words of
 * the command cannot stop the panel. The panel has no echo canceller and hears its own speaker, so three
 * holds keep it from stopping itself:
 * - [ONSET_GUARD_MS] after each playback starts, and after the listener itself starts, while a platform
 *   canceller re-converges and the model's frontend settles;
 * - [PROTECTION_MS] after the panel stops listening, which outlasts one match on audio still held from the
 *   command;
 * - while the reply's own text says the stop word, for as long as saying it can take ([textHoldMs]).
 *
 * Frames are scored on the delivering thread, as the wake word's are; [onStop] is called there, once. Every
 * hold runs on the capture clock, from the next frame after the event that set it.
 */
internal class StopWordListener(
    model: LoadedWakeWordModel,
    private val phase: () -> VoiceState,
    private val onStop: () -> Unit,
    private val log: (String) -> Unit = {},
) : PcmConsumer, AutoCloseable {

    // A word that starts with the stop word: "stop", and "stopped", "stopping" or "Stopp", which the
    // panel's own voice can say as readily and the model hears much alike.
    private val word = Regex(
        "(?<![\\p{L}\\p{N}])" + model.config.wakeWord.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    // A hit that is held clears the window and may come again a few inferences later: no cooldown, or a
    // "stop" said inside the command would leave the panel deaf to the real one for two seconds.
    private val detector = WakeWordDetector(listOf(model), ::heard, maxActive = 1, cooldownChunks = 0, warmupInferences = 0)

    // Set from any thread, taken by the delivering thread at its next frame. The listener starts guarded.
    private val guardNext = AtomicBoolean(true)
    private val replyHoldNext = AtomicReference<Long?>(null)

    // The delivering thread's own state.
    private var open = false
    private var fired = false
    private var lastHeld: String? = null
    private var guardUntilNs = Long.MIN_VALUE
    private var protectedUntilNs = Long.MIN_VALUE
    private var replyHeldUntilNs = Long.MIN_VALUE

    override fun onFrame(frame: PcmFrame) {
        val at = frame.timestampNs
        if (guardNext.getAndSet(false)) guardUntilNs = at + ONSET_GUARD_MS * NS_PER_MS
        replyHoldNext.getAndSet(null)?.let { replyHeldUntilNs = if (it > 0) at + it * NS_PER_MS else Long.MIN_VALUE }
        val now = phase().let { it == VoiceState.PROCESSING || it == VoiceState.RESPONDING }
        if (now && !open) protectedUntilNs = at + PROTECTION_MS * NS_PER_MS
        open = now
        detector.onFrame(frame)
    }

    override fun onDropped(count: Int) = detector.onDropped(count)

    /** Playback started: the panel's own sound is about to reach the microphone at a new level. */
    fun playbackStarted() = guardNext.set(true)

    /** The reply as written so far; a reply that says the stop word must not stop itself. */
    fun reply(text: String) = replyHoldNext.set(if (word.containsMatchIn(text)) textHoldMs(text) else 0L)

    private fun heard(hit: WakeWordHit) {
        if (!open || fired) return
        val at = hit.timestampNs
        val held = when {
            at < guardUntilNs -> "as playback starts"
            at < protectedUntilNs -> "as the panel stops listening"
            at < replyHeldUntilNs -> "while the reply says it"
            else -> null
        }
        val score = "%.2f".format(hit.probability)
        if (held != null) {
            // Once per reason: the panel's own voice inside a hold can match every few inferences.
            if (held != lastHeld) log("stop word heard at $score $held; ignored")
            lastHeld = held
            return
        }
        fired = true
        log("stop word heard at $score; stopping")
        onStop()
    }

    override fun close() = detector.close()

    companion object {
        const val ONSET_GUARD_MS = 1_500L
        const val PROTECTION_MS = 500L
        private const val NS_PER_MS = 1_000_000L
        private const val TEXT_HOLD_BASE_MS = 5_000L
        private const val TEXT_HOLD_PER_CHAR_MS = 80L
        private const val TEXT_HOLD_PER_IDEOGRAPH_MS = 300L
        private const val TEXT_HOLD_MAX_MS = 30_000L

        /** How long a reply that says the stop word can take to say: longer text, and ideographs, take longer. */
        fun textHoldMs(text: String): Long {
            var ideographs = 0
            var others = 0
            text.codePoints().forEach { if (Character.isIdeographic(it)) ideographs++ else others++ }
            return minOf(TEXT_HOLD_BASE_MS + others * TEXT_HOLD_PER_CHAR_MS + ideographs * TEXT_HOLD_PER_IDEOGRAPH_MS, TEXT_HOLD_MAX_MS)
        }
    }
}

/**
 * The bundled microWakeWord `stop` model (`assets/stopword/`, Apache-2.0, see its `NOTICE.txt`),
 * loaded fresh for each run. It lives outside `assets/wakeword/` so it is never offered as a wake word.
 */
internal object StopWordModel {
    private const val ASSET_DIR = "stopword"
    private const val ID = "stop"

    /**
     * The upstream manifest's cutoff is 0.5, tuned for a timer ringing on a Voice PE with an echo canceller;
     * without one, Ava Pro raised it to 0.83 against the panel's own speech, and this starts from theirs.
     */
    const val CUTOFF = 0.83f

    /** The model, or null when this ABI has no engine or the asset cannot be read. */
    fun load(context: Context): LoadedWakeWordModel? = runCatching {
        val manifest = context.assets.open("$ASSET_DIR/$ID.json").bufferedReader().use { it.readText() }
        val config = MicroWakeWordModelConfig.parse(ID, manifest).copy(probabilityCutoff = CUTOFF)
        val bytes = context.assets.open("$ASSET_DIR/${config.modelFile}").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        NativeMicroWakeWord.create(buffer, config)?.let { LoadedWakeWordModel(config, it) }
    }.getOrNull()
}
