package io.panelassistant.android.audio

/**
 * Adds Portal's last stable hardware privacy observation to Android's aggregate microphone mute.
 * Unknown or transitional events retain that observation; only a typed OFF can clear it, and Android
 * mute still wins. Observations and the existing mute publication run under one lock.
 */
internal class PortalMicrophonePrivacy(
    offered: () -> Boolean,
    androidMuted: () -> Boolean,
    publish: (muted: Boolean, initial: Boolean) -> Unit,
) : AutoCloseable {
    private var privacyOn = false
    private var closed = false
    val mute = MicrophoneMute(
        read = { offered() && (privacyOn || androidMuted()) },
        publish = publish,
    )

    /** Receipt-time state only: the watch schedules the Android read off the receiver thread. */
    @Synchronized
    fun observe(raw: Any?) {
        if (closed) return
        privacyOn = when (raw) {
            0.toByte() -> false
            1.toByte() -> true
            else -> return
        }
    }

    @Synchronized
    fun refresh() {
        if (!closed) mute.refresh()
    }

    @Synchronized
    override fun close() {
        closed = true
        mute.close()
    }
}
