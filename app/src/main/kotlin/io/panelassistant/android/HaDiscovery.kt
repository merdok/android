package io.panelassistant.android

/**
 * Pure decision for "could automatic Home Assistant discovery have worked on this panel at all, and if
 * not, why?" — so setup can tell a user to type an address instead of leaving them at an empty field.
 *
 * mDNS is link-local by definition, so a panel on a different network segment from Home Assistant can
 * never discover it, and a bare null cannot be told apart from "found nothing yet".
 *
 * Outcomes are ranked by how much is actually known, and the wording must never overclaim.
 * [DiscoveryReason.MDNS_NOT_RUNNING] and [DiscoveryReason.NO_MULTICAST_LOCK] are facts about this panel
 * (we did not, or could not, listen). [DiscoveryReason.NO_RESPONSES_AT_ALL] is only a guess and is
 * phrased as one.
 *
 * Pure — no Android imports, unit-tested in HaDiscoveryTest.
 */
enum class DiscoveryOutcome {
    /** No browse has been run yet in this process. */
    NOT_ATTEMPTED,

    /** A browse is in flight; its result is not known yet. */
    SEARCHING,

    /** Home Assistant was discovered. */
    FOUND,

    /** Discovery ran and could plausibly have worked, but nothing matched. Retrying may help. */
    NONE_FOUND,

    /** Discovery cannot work on this panel/network. Retrying will not help; the user must type the address. */
    UNAVAILABLE,
}

enum class DiscoveryReason {
    NONE,

    /** The mDNS stack is not running, so nothing was listened for. Certain. */
    MDNS_NOT_RUNNING,

    /** No multicast lock, so multicast reception is not guaranteed. Certain. */
    NO_MULTICAST_LOCK,

    /** A full browse saw no mDNS services of any kind. A guess — phrase it as one. */
    NO_RESPONSES_AT_ALL,

    /** The browse itself failed. */
    ERROR,
}

data class DiscoveryResult(
    val outcome: DiscoveryOutcome = DiscoveryOutcome.NOT_ATTEMPTED,
    /** The discovered base URL, only when [outcome] is [DiscoveryOutcome.FOUND]. */
    val value: String = "",
    val reason: DiscoveryReason = DiscoveryReason.NONE,
    /** Wall-clock of the attempt this result describes; zero when never attempted. */
    val attemptedAtMs: Long = 0L,
) {
    /** True when retrying cannot help, so the UI should ask the user to type the address instead. */
    val hopeless: Boolean get() = outcome == DiscoveryOutcome.UNAVAILABLE
}

/** Sentinel for [HaDiscovery.classify]'s `servicesSeen` when the caller cannot count responses. */
const val SERVICES_SEEN_UNKNOWN = -1

object HaDiscovery {
    /**
     * Prefer the mDNS record's own hostname over an IP-literal advertised URL.
     *
     * Home Assistant instances commonly advertise `internal_url`/`base_url` in IP form; suggestions
     * built from that pin panels to one IPv4 address (hardware review: "still a raw IPv4"). The record's
     * `server` field (`homeassistant.local.`-style) names the same machine — same trust level, same
     * record — and resolves over IPv6 too, so an IP-literal host is rewritten to it. A hostname-form
     * advertised URL is left untouched, and any parse trouble returns the URL unchanged.
     */
    fun preferServerHostname(url: String?, serverHost: String?): String? {
        url ?: return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return url
        if (!hostIsIpLiteral(host)) return url
        val name = serverHost?.trim()?.trimEnd('.')
            ?.takeIf { it.isNotBlank() && !hostIsIpLiteral(it) } ?: return url
        return runCatching {
            val u = java.net.URI(url)
            java.net.URI(u.scheme, null, name, u.port, u.path, u.query, u.fragment).toString()
        }.getOrDefault(url)
    }

    private fun hostIsIpLiteral(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        return bare.contains(':') || bare.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }

    /**
     * Fold the probe signals into one honest outcome. Order is the honesty ranking: a successful discovery
     * settles it; then the two facts about this panel; then, last and weakest, the "nothing answered at
     * all" guess.
     *
     * @param servicesSeen  mDNS services of any type observed, or [SERVICES_SEEN_UNKNOWN] to skip the guess
     */
    fun classify(
        mdnsRunning: Boolean,
        multicastLockHeld: Boolean,
        servicesSeen: Int,
        discoveredUrl: String,
        attemptedAtMs: Long,
        failed: Boolean = false,
    ): DiscoveryResult {
        fun result(outcome: DiscoveryOutcome, reason: DiscoveryReason = DiscoveryReason.NONE) =
            DiscoveryResult(outcome = outcome, reason = reason, attemptedAtMs = attemptedAtMs)

        if (discoveredUrl.isNotBlank()) {
            return DiscoveryResult(DiscoveryOutcome.FOUND, discoveredUrl, DiscoveryReason.NONE, attemptedAtMs)
        }
        if (!mdnsRunning) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.MDNS_NOT_RUNNING)
        if (!multicastLockHeld) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.NO_MULTICAST_LOCK)
        if (failed) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.ERROR)
        if (servicesSeen == 0) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.NO_RESPONSES_AT_ALL)
        return result(DiscoveryOutcome.NONE_FOUND)
    }

    /**
     * Plain-language reason automatic discovery could not work, for use inside a sentence — null when the
     * outcome is not [DiscoveryOutcome.UNAVAILABLE]. Confidence is carried in the grammar: the certain and
     * inferred reasons state what is so, the guess says "may".
     */
    fun unavailableExplanation(result: DiscoveryResult): String? = when {
        result.outcome != DiscoveryOutcome.UNAVAILABLE -> null
        else -> when (result.reason) {
            DiscoveryReason.MDNS_NOT_RUNNING ->
                "this panel's network discovery service is not running"
            DiscoveryReason.NO_MULTICAST_LOCK ->
                "this panel cannot receive network discovery traffic"
            DiscoveryReason.NO_RESPONSES_AT_ALL ->
                "nothing answered this panel's discovery request, so Home Assistant may be on another network segment or discovery traffic may be blocked"
            DiscoveryReason.ERROR ->
                "this panel's network discovery attempt failed"
            DiscoveryReason.NONE -> null
        }
    }
}
