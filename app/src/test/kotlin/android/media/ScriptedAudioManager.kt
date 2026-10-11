package android.media

/**
 * An [AudioManager] whose music stream volume a test sets, as the panel's volume keys would. It lives in
 * `android.media` because that is the only place the stub's constructor can be called from.
 */
internal class ScriptedAudioManager : AudioManager() {
    @Volatile var percent: Int = 0

    override fun getStreamMaxVolume(streamType: Int): Int = 100

    override fun getStreamVolume(streamType: Int): Int = percent
}
