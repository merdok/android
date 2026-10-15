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
