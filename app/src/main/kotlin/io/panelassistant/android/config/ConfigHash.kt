package io.panelassistant.android.config

import java.security.MessageDigest

/**
 * Stable short fingerprint of the panel's settings, exposed as `cfg=` on `/health`: a client that
 * watches the health line sees the settings change underneath it (an API POST, an HA entity, another
 * browser), and config import compares it — the config-state sibling of the per-install build token.
 * Order-insensitive (sorted keys) so map iteration order can never fake a change.
 */
object ConfigHash {
    fun of(values: Map<String, String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        // NUL-separated, not "k=v" — keys/values containing '=' must not be able to collide
        // ("a"→"b=x" vs "a=b"→"x" serialize identically under a naive joiner).
        for ((k, v) in values.entries.sortedBy { it.key }) md.update("$k\u0000$v\u0000".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(8)
    }
}
