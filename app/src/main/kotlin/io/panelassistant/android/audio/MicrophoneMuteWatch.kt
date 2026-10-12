package io.panelassistant.android.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Follows Android's microphone mute: reads it once at [start], then again on each change Android
 * announces, and calls [onChange] when it differs. Before Android 9 there is no announcement, and the
 * read reaches the audio HAL, which on the Sonoff panels does not implement the mute query and logs a
 * warning per read (2026-10-05), so it is never polled; those panels have no mute control to follow.
 * Portal's protected hardware privacy events supplement Android when [portalPrivacy] is enabled.
 * Only an [offered] microphone is ever reported muted.
 */
internal class MicrophoneMuteWatch(
    private val context: Context,
    offered: () -> Boolean,
    onChange: (muted: Boolean, initial: Boolean) -> Unit,
    private val portalPrivacy: () -> Boolean = { false },
) : AutoCloseable {
    private val observation = PortalMicrophonePrivacy(
        offered = offered,
        androidMuted = { context.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true },
        publish = onChange,
    )
    val mute get() = observation.mute
    private var receiver: BroadcastReceiver? = null
    private var privacyReceiver: BroadcastReceiver? = null
    private var started = false
    private var closed = false

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (closed || started) return
        started = true
        if (portalPrivacy()) {
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    synchronized(this@MicrophoneMuteWatch) {
                        if (closed) return
                        observation.observe(privacyExtra(intent))
                        scope.launch { observation.refresh() }
                    }
                }
            }
            runCatching {
                val filter = IntentFilter(PRIVACY_ACTION)
                val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(listener, filter)
                }
                privacyReceiver = listener
                // Seed the first reading before launching it: an ON sticky must never produce a
                // false initial unmute while its duplicate initial delivery is still queued.
                observation.observe(privacyExtra(sticky))
            }.onFailure { Log.w("ha-paneld/voice", "hardware microphone privacy could not be followed", it) }
        }
        scope.launch { observation.refresh() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch { observation.refresh() }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(listener, filter)
            }
        }.onSuccess { receiver = listener }
            .onFailure { Log.w("ha-paneld/voice", "microphone mute changes could not be followed", it) }
    }

    /** Unregister; nothing is reported afterwards, and a later [start] does nothing. */
    @Synchronized
    override fun close() {
        closed = true
        observation.close()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        privacyReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        privacyReceiver = null
    }

    private fun privacyExtra(intent: Intent?): Any? = runCatching {
        @Suppress("DEPRECATION")
        intent?.takeIf { it.action == PRIVACY_ACTION }?.extras?.get(PRIVACY_EXTRA)
    }.getOrNull()

    private companion object {
        const val PRIVACY_ACTION = "android.intent.action.PRIVACY_CHANGE"
        const val PRIVACY_EXTRA = "com.facebook.alohasdk.platformsecurestate.privacystate"
    }
}
