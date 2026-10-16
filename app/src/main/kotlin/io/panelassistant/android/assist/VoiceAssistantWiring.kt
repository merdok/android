package io.panelassistant.android.assist

import android.content.Context
import android.media.MediaRecorder
import io.panelassistant.android.Config
import io.panelassistant.android.audio.MicPurpose
import io.panelassistant.android.audio.MicState
import io.panelassistant.android.audio.MicrophoneSource
import io.panelassistant.android.audio.MicrophoneSourceLifecycle
import io.panelassistant.android.builtInRendererReady
import io.panelassistant.android.media.AudioPlaybackCoordinator
import io.panelassistant.android.panelassistant.PanelAssistantVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** The Home Assistant pipeline catalogue, read through one short-lived authenticated socket per call. */
internal class HaAssistPipelineDirectory(private val config: Config) : AssistPipelineDirectory {
    override suspend fun list(): AssistPipelineDirectory.Result {
        if (!config.builtInRendererReady()) {
            return AssistPipelineDirectory.Result.NotConfigured("Home Assistant is not configured")
        }
        return when (val result = AssistPipelineClient(config).listPipelines()) {
            is AssistCatalogResult.Catalog -> AssistPipelineDirectory.Result.Available(
                pipelines = result.catalog.pipelines.map { AssistPipelineDirectory.Pipeline(it.id, it.name) },
                preferred = result.catalog.preferredId.orEmpty(),
            )
            is AssistCatalogResult.Failed -> when (result.error.code) {
                AssistPipelineClient.CODE_NOT_CONFIGURED,
                AssistPipelineClient.CODE_CREDENTIALS_UNAVAILABLE,
                -> AssistPipelineDirectory.Result.NotConfigured(result.error.message)
                else -> AssistPipelineDirectory.Result.Unavailable(result.error.message)
            }
        }
    }
}

/**
 * Plays a reply through the panel's single announcement coordinator and returns only once that
 * playback has actually finished.
 *
 * The coordinator keeps only the newest announcement, so a reply can be replaced before it is heard.
 * That is an ordinary outcome on a panel that also speaks for other reasons, and it is reported as
 * one: this waits for the exact generation it submitted and raises the typed playback fault when
 * that generation ends any way other than completing. Returning normally is the run's proof that the
 * panel actually spoke, so it must never be the answer to "the queue accepted it".
 */
internal class AnnouncementLanePlayback(
    private val audio: AudioPlaybackCoordinator,
    private val pollMs: Long = POLL_MS,
    /** Called once the reply has been accepted for playback, so the panel can report that it is speaking. */
    private val onStarted: () -> Unit = {},
    /** Reports the exact accepted generation to callers that may later cancel only their own audio. */
    private val onGeneration: (Long) -> Unit = {},
) : AssistPlayback {
    override suspend fun play(url: String) {
        // The generation comes back from the submission itself. Reading the snapshot afterwards can
        // return a later announcement's generation, which would leave this run watching, and
        // reporting on, playback that is not its own.
        watch(audio.submitForGeneration(url, speech = true))
    }

    override suspend fun playStream(streamId: String): Boolean {
        watch(audio.submitStreamForGeneration(streamId))
        return audio.streamEnded(streamId)?.listenAfter == true
    }

    // The coordinator refuses to cancel any generation but the current one, so a later sound is safe.
    // Set before [watch] checks for cancellation, which [silence]'s caller sets before reading it.
    @Volatile private var last: Long? = null

    override fun silence() {
        last?.let(audio::cancelGeneration)
    }

    private suspend fun watch(submitted: Long?) {
        val generation = submitted
            ?: throw AssistPlaybackException(
                AssistPipelineClient.CODE_PLAYBACK_FAILED,
                "The announcement coordinator is no longer accepting playback",
            )
        last = generation
        // A run stopped while this sound was being submitted was silenced by the generation before this
        // one, so it silences its own. Named first and checked after, one of the two always sees it.
        if (!currentCoroutineContext().isActive) {
            audio.cancelGeneration(generation)
            currentCoroutineContext().ensureActive()
        }
        onGeneration(generation)
        onStarted()
        while (true) {
            val now = audio.snapshot()
            if (now.generation != generation) {
                throw AssistPlaybackException(
                    AssistPipelineClient.CODE_PLAYBACK_SUPERSEDED,
                    "A later announcement replaced the reply before it finished",
                )
            }
            when (now.state) {
                AudioPlaybackCoordinator.State.QUEUED,
                AudioPlaybackCoordinator.State.ACTIVE,
                -> delay(pollMs)
                AudioPlaybackCoordinator.State.IDLE -> return
                AudioPlaybackCoordinator.State.FAILED -> throw AssistPlaybackException(
                    AssistPipelineClient.CODE_PLAYBACK_FAILED,
                    now.error ?: "The reply failed to play",
                )
                AudioPlaybackCoordinator.State.CLOSED -> throw AssistPlaybackException(
                    AssistPipelineClient.CODE_PLAYBACK_FAILED,
                    "The announcement coordinator closed while the reply was playing",
                )
            }
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}

/**
 * Builds the panel's one microphone source on first use and rebuilds it only when the configured
 * audio source changes while nothing holds a lease, so a setting change never takes capture away
 * from a live consumer.
 */
internal class ConfiguredMicrophoneSource(
    private val audioSource: () -> String?,
    private val build: (Int) -> MicrophoneSource,
    private val scope: CoroutineScope,
    /** Re-applies the setting; called once a stale source is no longer held by the camera stream. */
    private val onFree: () -> Unit,
) {
    private var current: MicrophoneSource? = null
    private var currentSource: Int = -1
    private var closed = false
    private var waiting: Job? = null

    @Synchronized
    fun get(): MicrophoneSource {
        check(!closed) { "microphone source is shut down" }
        val wanted = audioSourceFor(audioSource())
        val existing = current
        if (existing != null && (currentSource == wanted || existing.state.value !is MicState.Closed)) {
            // A stale source kept for the camera stream: re-apply the setting once the stream lets go.
            // Voice holders re-apply through the coordinator's own start(), so only a stream holder waits.
            if (currentSource != wanted && waiting?.isActive != true && existing.state.value.streaming()) {
                waiting = scope.launch {
                    existing.state.first { !it.streaming() }
                    onFree()
                }
            }
            return existing
        }
        (existing as? MicrophoneSourceLifecycle)?.close()
        val built = build(wanted)
        current = built
        currentSource = wanted
        return built
    }

    @Synchronized
    fun shutdown(timeoutMs: Long): Boolean {
        closed = true
        waiting?.cancel()
        return (current as? MicrophoneSourceLifecycle)?.shutdown(timeoutMs) ?: true
    }

    private fun MicState.streaming(): Boolean = when (this) {
        is MicState.Open -> holders.any { it.purpose == MicPurpose.STREAM }
        is MicState.Error -> holders.any { it.purpose == MicPurpose.STREAM }
        MicState.Closed -> false
    }

    companion object {
        fun audioSourceFor(setting: String?): Int = when (setting?.trim()?.lowercase()) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}

/**
 * What the service does with each microphone mute reading: show it on the dashboard (announcing a change,
 * quietly correcting a first reading) and let [voice] re-check or stand its microphone check down.
 */
internal fun publishMicrophoneMute(voice: () -> VoiceAssistantCoordinator): (Boolean, Boolean) -> Unit = { muted, initial ->
    VoiceAttention.microphoneMuted(muted, announce = !initial)
    voice().microphoneMuteChanged()
}

/**
 * The panel's turn runner: the chime is muted from what the turn hears, and every event of the turn reaches
 * [conversation], the card on screen.
 */
internal fun satelliteTurnRunner(
    voice: PanelAssistantVoice,
    log: (String) -> Unit,
    conversation: VoiceConversation = VoiceAttention.conversation,
): SatelliteTurnRunner = SatelliteTurnRunner(voice, log, chime = { VoiceAttention.chime }, onEvent = conversation::event)

/** Assemble the coordinator for the running service. */
internal fun voiceAssistantCoordinator(
    context: Context,
    config: Config,
    scope: CoroutineScope,
    audio: AudioPlaybackCoordinator,
    microphone: () -> io.panelassistant.android.audio.MicrophonePresence,
    onMicrophoneStatus: () -> Unit,
    muted: () -> Boolean,
    foregroundMicrophone: (Boolean) -> Boolean,
    state: VoiceStateAuthority,
    engineFactory: WakeWordEngineFactory,
    voice: PanelAssistantVoice,
    log: (String) -> Unit,
    source: () -> MicrophoneSource,
): VoiceAssistantCoordinator {
    val runner = satelliteTurnRunner(voice, log)
    return VoiceAssistantCoordinator(
        scope = scope,
        settings = {
            VoiceSettings.parse(
                config.voiceEnabled,
                config.voiceWakeWords,
                config.voicePipelines,
                config.voiceMicGainDb,
                config.voiceStopWord,
            )
        },
        microphone = microphone,
        source = source,
        engineFactory = engineFactory,
        runnerFactory = { runner },
        playback = AnnouncementLanePlayback(audio, onStarted = { state.set(VoiceState.RESPONDING) }),
        foregroundMicrophone = foregroundMicrophone,
        state = state,
        attention = VoiceAttention::cue,
        onMicrophoneStatus = onMicrophoneStatus,
        muted = muted,
        stopWordModel = { StopWordModel.load(context) },
        log = log,
    ).also { VoiceAttention.prepare(context) }
}
