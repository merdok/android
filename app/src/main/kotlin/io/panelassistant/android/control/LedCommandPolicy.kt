package io.panelassistant.android.control

import io.panelassistant.android.hardware.LedEffects
import io.panelassistant.android.panelassistant.CommandValue

/** Pure parsing, persistence, and observation policy for the command-authoritative RGB LED. */
internal object LedCommandPolicy {
    data class Desired(
        val on: Boolean,
        val brightness: Int = 0,
        val red: Int = 0,
        val green: Int = 0,
        val blue: Int = 0,
        val effect: LedEffects.Effect? = null,
    ) {
        fun storedColor(): String = if (on) "1,$brightness,$red,$green,$blue" else "0,0,0,0,0"
        fun storedEffect(): String = if (on) effect?.effectName.orEmpty() else ""
    }

    fun stored(color: String, effect: String): Desired {
        val values = color.split(",").mapNotNull { it.toIntOrNull() }
        if (values.size != 5 || values[0] != 1) return Desired(on = false)
        return Desired(
            on = true,
            brightness = clamp(values[1]),
            red = clamp(values[2]),
            green = clamp(values[3]),
            blue = clamp(values[4]),
            effect = LedEffects.Effect.from(effect),
        )
    }

    /** A level or colour the command leaves out keeps the previous one; a missing effect clears it. */
    fun command(light: CommandValue.Light, previous: Desired): Desired {
        if (!light.on) return Desired(on = false)
        val lit = previous.takeIf { it.on }
        return Desired(
            on = true,
            brightness = clamp(light.brightness ?: lit?.brightness ?: 255),
            red = clamp(light.rgb?.first ?: lit?.red ?: 255),
            green = clamp(light.rgb?.second ?: lit?.green ?: 255),
            blue = clamp(light.rgb?.third ?: lit?.blue ?: 255),
            effect = LedEffects.Effect.from(light.effect),
        )
    }

    /** Null means the desired state is not backed by a confirmed current actuation. */
    fun statePayload(desired: Desired, actuationKnown: Boolean, effectStatus: LedEffectController.Status): String? {
        if (!actuationKnown) return null
        if (!desired.on) return """{"state":"OFF"}"""
        if (desired.effect != null && effectStatus != LedEffectController.Status.RUNNING) return null
        val effect = desired.effect?.effectName ?: "none"
        return """{"state":"ON","color_mode":"rgb","brightness":${desired.brightness},"color":{"r":${desired.red},"g":${desired.green},"b":${desired.blue}},"effect":"$effect"}"""
    }

    private fun clamp(value: Int) = value.coerceIn(0, 255)
}
