package io.panelassistant.android.util

import java.util.Locale

/**
 * User-owned bound on address-family selection for every connection the panel makes to Home Assistant:
 * the Panel Assistant transport, the Home Assistant WebSockets, Assist, presence, entity learning, the
 * HA link and, while it exists, MQTT.
 */
internal enum class AddressFamilyPolicy(
    val configValue: String,
    val initialPreferIpv4: Boolean,
    val ipv4Only: Boolean,
) {
    AUTOMATIC("Automatic", initialPreferIpv4 = false, ipv4Only = false),
    PREFER_IPV4("Prefer IPv4", initialPreferIpv4 = true, ipv4Only = false),
    FORCE_IPV4("Force IPv4", initialPreferIpv4 = true, ipv4Only = true),
    ;

    companion object {
        fun fromConfig(value: String): AddressFamilyPolicy = entries.firstOrNull {
            it.configValue.lowercase(Locale.ROOT) == value.trim().lowercase(Locale.ROOT)
        } ?: AUTOMATIC
    }
}
