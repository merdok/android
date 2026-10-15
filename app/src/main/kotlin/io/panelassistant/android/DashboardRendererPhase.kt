package io.panelassistant.android

/**
 * The built-in dashboard renderer's phase: the one state [DashboardActivity] decides its handshake
 * watchdog, covers, reloads and consent from. It replaces three flags that were set and cleared
 * independently (frontend connected, reconnecting page shown, login refused), whose combinations were
 * where rebuilds went wrong: a reconnecting flag outliving its WebView, so the next page's network error
 * could not raise the cover, or a refused login still reported connected.
 *
 * Whether the screen is lit is not a phase: every phase can be lit or dark, and the activity freezes or
 * settles the WebView from the screen and the phase together.
 */
internal enum class RendererPhase {
    /** No dashboard WebView: a native status, admission, network-wait or entity-hold screen owns the display. */
    NONE,

    /** The bare on-panel Home Assistant sign-in WebView: no bus, no handshake, nothing to watch. */
    SIGN_IN,

    /** A dashboard load is in flight, or the frontend dropped its connection and has not reported back. */
    LOADING,

    /** Home Assistant's frontend reported itself connected over the external bus. */
    CONNECTED,

    /** A main-frame load failed: the reconnecting cover stands over a blank page while the watchdog retries. */
    RECONNECTING,

    /** Home Assistant refused the login settings: the latch page stands, and nothing retries until consent. */
    AUTH_LATCHED;

    /** The phase [event] leaves the renderer in. */
    fun after(event: RendererEvent): RendererPhase = when (event) {
        // A refused login belongs to the credential, not to the WebView that saw it: a rebuild keeps it, and
        // the rebuilt WebView shows the latch page again.
        RendererEvent.TORN_DOWN -> if (this == AUTH_LATCHED) AUTH_LATCHED else NONE
        // The sign-in has no credential yet, so there is nothing left to refuse.
        RendererEvent.SIGN_IN_SHOWN -> SIGN_IN
        // Only consent replaces a refused login; a load that starts without it stays latched.
        RendererEvent.LOAD_STARTED -> if (this == AUTH_LATCHED || this == SIGN_IN) this else LOADING
        RendererEvent.CONNECTED -> CONNECTED
        RendererEvent.DISCONNECTED -> if (this == CONNECTED) LOADING else this
        RendererEvent.MAIN_FRAME_FAILED -> if (this == LOADING || this == CONNECTED) RECONNECTING else this
        RendererEvent.LOGIN_REFUSED -> AUTH_LATCHED
        RendererEvent.CONSENT -> if (this == AUTH_LATCHED || this == RECONNECTING) LOADING else this
    }

    /** Whether the handshake watchdog watches this phase: only a dashboard document that should connect. */
    val watched: Boolean get() = this == LOADING || this == RECONNECTING
}

/** What happens to the renderer; [RendererPhase.after] says where each one leaves it. */
internal enum class RendererEvent {
    /** The dashboard WebView went away: torn down for a rebuild, a status screen, or a crashed renderer. */
    TORN_DOWN,
    SIGN_IN_SHOWN,
    LOAD_STARTED,
    CONNECTED,
    DISCONNECTED,
    MAIN_FRAME_FAILED,
    LOGIN_REFUSED,

    /** A navigate, an explicit reload or a configuration change: the person's consent to try again. */
    CONSENT,
}

/**
 * Why the live renderer no longer embodies its configuration, in the order the renderer reports it. One
 * rebuild applies every cause found; each says what that rebuild must reset before building again.
 */
internal enum class RendererRebuildCause(
    /** Ask Home Assistant's compatibility again: the admitted address or route is no longer the one wanted. */
    val readmit: Boolean,
    /** Resolve the home dashboard again: its owner (credential and configured path) or its route moved. */
    val resolveHome: Boolean,
    /** A change someone made is the consent to retry a refused login and restart the handshake cadence. */
    val consent: Boolean,
) {
    /** The Home Assistant route changed: a failover to a peer address, or a new address or credential. */
    ROUTE(readmit = true, resolveHome = true, consent = false),

    /** Credentials arrived while the on-panel sign-in was showing (a browser sign-in, a restore). */
    SIGNED_IN(readmit = false, resolveHome = false, consent = true),

    /** The Home Assistant address differs from the one admission checked. */
    ENDPOINT(readmit = true, resolveHome = false, consent = true),

    /** The home dashboard's owner (credential or configured path) differs from the one resolved. */
    HOME_DASHBOARD(readmit = false, resolveHome = true, consent = true),

    /** The entity filter or learning mode differs from the one baked into the document-start script. */
    ENTITY_FILTER(readmit = false, resolveHome = false, consent = true),

    /** The dashboard theme policy differs from the one baked into the document-start script. */
    THEME(readmit = false, resolveHome = false, consent = true),
}

/**
 * Whether a change to [key] makes the renderer reconcile at once, whoever wrote it (a settings page, a
 * Panel Assistant entity, an import): every setting it bakes in or was admitted with. The entity-filter
 * settings are the exception, because their owner, the learner, rebuilds once it has re-learned the set;
 * reconciling them here too would rebuild twice.
 */
internal fun rendererReconcilesOnChange(key: String, sdkInt: Int): Boolean =
    when (io.panelassistant.android.config.SettingsRegistry.rendererApply(key, sdkInt)) {
        io.panelassistant.android.config.RendererApply.READMIT -> true
        io.panelassistant.android.config.RendererApply.REBUILD -> !key.startsWith("dashboard_entity_")
        else -> false
    }
