package io.panelassistant.android.control

import java.io.File

/**
 * The panel's own network hardware addresses, for Panel Assistant to put on its Home Assistant device
 * so the card lists other integrations' devices for the same hardware (the Shelly integration's, say)
 * as linked devices.
 *
 * Read from sysfs on every status, because an interface can appear after start-up. Only Wi-Fi and
 * Ethernet interfaces count, and a randomised, placeholder (`02:00:00:00:00:00`) or multicast address
 * is left out: it names no hardware another integration could share. A panel whose Android denies the
 * read sends none.
 */
internal class NetworkMacs(private val netRoot: File) {
    fun read(): List<String> =
        (netRoot.listFiles() ?: emptyArray())
            .filter { iface -> PREFIXES.any { iface.name.startsWith(it) } }
            .mapNotNull { iface -> runCatching { File(iface, "address").readText() }.getOrNull() }
            .mapNotNull(::hardwareAddress)
            .distinct()
            .sorted()
            .take(MAX_ADDRESSES)

    companion object {
        private val PREFIXES = listOf("wlan", "eth", "en")
        private const val MAX_ADDRESSES = 4
        private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        /** A globally administered unicast address in lower-case colon form, or null. */
        internal fun hardwareAddress(raw: String): String? {
            val mac = raw.trim().lowercase()
            if (!MAC.matches(mac)) return null
            val first = mac.substring(0, 2).toInt(16)
            // Bit 1: locally administered (randomised or placeholder); bit 0: multicast.
            if (first and 0x03 != 0) return null
            if (mac == "00:00:00:00:00:00") return null
            return mac
        }

        val shared = NetworkMacs(File("/sys/class/net"))
    }
}
