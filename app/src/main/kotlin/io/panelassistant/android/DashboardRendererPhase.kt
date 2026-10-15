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

    /** The credential differs from the one the dashboard was built with: consent, so a refused login is
     *  asked again once someone has changed the credential it refused. */
    CREDENTIAL(readmit = true, resolveHome = true, consent = true),

    /** The home dashboard's owner (credential or configured path) differs from the one resolved. */
    HOME_DASHBOARD(readmit = false, resolveHome = true, consent = true),

    /** The entity filter or learning mode differs from the one baked into the document-start script. */
    ENTITY_FILTER(readmit = false, resolveHome = false, consent = true),

    /** The dashboard theme policy differs from the one baked into the document-start script. */
    THEME(readmit = false, resolveHome = false, consent = true),
}

/**
 * What the reconcile compares. As embodied by the live renderer, a field is null where nothing embodies it
 * yet: [admittedUrl] until admission passes, [homeOwner] until the home dashboard is resolved or resolving,
 * [authOwner] until a dashboard WebView has been built, and [filter] and [theme] while no dashboard WebView
 * exists, because the next build bakes whatever is configured then. As wanted, every field is set.
 */
internal data class RendererConfiguration(
    val admittedUrl: String?,
    val homeOwner: HomeDashboardResolutionOwner?,
    val authOwner: HaAuthOwner?,
    val filter: String?,
    val theme: String?,
)

/** Every cause for which [embodied] no longer matches [wanted], in [RendererRebuildCause] order. */
internal fun rendererRebuildCauses(
    routeChanged: Boolean,
    signedIn: Boolean,
    embodied: RendererConfiguration,
    wanted: RendererConfiguration,
): List<RendererRebuildCause> = buildList {
    fun differs(now: Any?, next: Any?) = now != null && now != next
    if (routeChanged) add(RendererRebuildCause.ROUTE)
    if (signedIn) add(RendererRebuildCause.SIGNED_IN)
    if (differs(embodied.admittedUrl, wanted.admittedUrl)) add(RendererRebuildCause.ENDPOINT)
    if (differs(embodied.authOwner, wanted.authOwner)) add(RendererRebuildCause.CREDENTIAL)
    if (differs(embodied.homeOwner, wanted.homeOwner)) add(RendererRebuildCause.HOME_DASHBOARD)
    if (differs(embodied.filter, wanted.filter)) add(RendererRebuildCause.ENTITY_FILTER)
    if (differs(embodied.theme, wanted.theme)) add(RendererRebuildCause.THEME)
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

/**
 * Which renderer settings the dashboard on screen shows: the bookkeeping behind
 * [io.panelassistant.android.control.BuiltinDashboard.pendingRendererSettings]. A value is on screen once
 * a load carrying it has connected (the moment the restart card drops), lit or dark; a load that fails
 * leaves it pending. Values are the ones [rendererShownValue] reads.
 */
internal class RendererEmbodiment {
    private var loading = emptyMap<String, String>()
    private var shown = emptyMap<String, String>()

    /** A dashboard WebView was built: it carries every value it was built and admitted with. */
    fun built(values: Map<String, String>) { loading = values }

    /** A load started in the current WebView: it also carries the values a page load picks up. */
    fun loadStarted(values: Map<String, String>) { loading = loading + values }

    /** The load connected: what it carries is on screen. */
    fun connected() { shown = loading }

    /** The renderer found nothing to rebuild for [values]: the WebView already embodies them. */
    fun reconciled(values: Map<String, String>, connected: Boolean) {
        loading = loading + values
        if (connected) shown = shown + values
    }

    /** The keys of [current] whose value is not on screen yet. */
    fun pending(current: Map<String, String>): Set<String> = current.filter { (key, value) -> shown[key] != value }.keys
}

/**
 * [key]'s value as the dashboard on screen embodies it: the address without a trailing slash, a token only
 * where it is the static credential (an access token that a refresh token rotates is not something a page
 * shows), every other setting as stored.
 */
internal fun rendererShownValue(config: Config, key: String): String = when (key) {
    "ha_url" -> config.haUrl.trim().trimEnd('/')
    "ha_token" -> if (config.haRefreshToken.isBlank()) config.haToken else ""
    else -> io.panelassistant.android.config.SettingsRegistry.spec(key)?.let(config::getRaw).orEmpty()
}
