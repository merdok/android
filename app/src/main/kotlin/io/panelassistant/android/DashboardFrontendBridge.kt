package io.panelassistant.android

import android.os.Handler
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.panelassistant.android.dashboard.EntityFilterProtocol
import io.panelassistant.android.dashboard.EntityFilterTelemetry
import io.panelassistant.android.dashboard.EntityLearningProtocol
import io.panelassistant.android.dashboard.EntityLearningRuntime
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

internal class ExternalV2BridgeUnavailable(cause: Throwable) : RuntimeException(cause)

private data class PendingV2Auth(
    val config: Config,
    val generation: Long,
    val session: ExternalBusController.Session,
    val payload: String,
    val force: Boolean,
)

private data class V2BridgeDocument(
    val config: Config,
    val generation: Long,
    val session: ExternalBusController.Session,
    val filterLease: EntityFilterTelemetry.Lease?,
)

/**
 * The dashboard's half of the Home Assistant frontend bridge: the `externalAppV2` and `haPaneldV2`
 * listeners, the per-document external-bus session, the bounded external-auth queue and the V2
 * handshake gate. [DashboardActivity] owns the renderer and decides what a connection event means;
 * this owns the wire. Every callback posts on the activity's own [main] Handler, so the activity's
 * `removeCallbacksAndMessages(null)` at destroy still clears all of them.
 */
internal class DashboardFrontendBridge(
    private val main: Handler,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        /** The renderer generation is still the activity's live one. */
        fun rendererCurrent(generation: Long): Boolean
        /** The connection route the renderer was built on is still the configured one. */
        fun routeCurrent(): Boolean
        fun currentWeb(): WebView?
        /** Read at document start, never captured: the activity replaces its lease as the filter changes. */
        fun entityFilterLease(): EntityFilterTelemetry.Lease?
        fun onConnectionStatus(event: ExternalBusProtocol.ConnectionEvent, generation: Long, session: ExternalBusController.Session)
        fun onThemeUpdate(generation: Long, session: ExternalBusController.Session)
        fun onAuthRejected(generation: Long)
        fun openConfigScreen()
    }

    // One bounded owner for typed app→frontend commands and their per-document result correlation.
    private val externalBus = ExternalBusController()
    var session: ExternalBusController.Session? = null
        private set
    private var v2BridgeDocument: V2BridgeDocument? = null
    private var v2ListenerView: WebView? = null
    private var expectedPageStartUrl: String? = null
    private val busTimeouts = LinkedHashMap<Int, Runnable>()
    private val v2Handshake = V2HandshakeGate(V2_MISSING_RELOAD_LIMIT)
    // V2 callbacks arrive on the UI thread. Keep at most one blocking auth resolution in flight and
    // one latest request waiting behind it; repeated frontend requests cannot grow coroutine retention.
    private val authQueue = BoundedAuthQueue<PendingV2Auth>(
        sameOwner = { left, right -> left.session == right.session },
        forced = PendingV2Auth::force,
    )

    fun current(generation: Long, session: ExternalBusController.Session): Boolean =
        host.rendererCurrent(generation) && externalBus.owns(session) && host.routeCurrent()

    /** Rotate the bounded per-document controller before navigation. The listeners themselves stay
     * attached across ordinary HA reloads/redirects, as in upstream Android; their callback resolves
     * this current context rather than retaining a replaced document's credentials or session. */
    fun beginDocument(
        view: WebView,
        config: Config,
        generation: Long,
    ): ExternalBusController.Session {
        clearBusTimeouts()
        authQueue.clear()
        val session = externalBus.beginDocument(generation, config.dashboardNativeKiosk)
        this.session = session
        v2Handshake.begin(session)
        v2BridgeDocument = V2BridgeDocument(config, generation, session, host.entityFilterLease())
        try {
            if (v2ListenerView !== view) installListeners(view, config)
        } catch (error: Throwable) {
            externalBus.invalidate()
            this.session = null
            v2BridgeDocument = null
            throw ExternalV2BridgeUnavailable(error)
        }
        return session
    }

    /** The document a WebView about to be created will load: opened before the listeners are installed. */
    fun openDocumentForCreate(config: Config, generation: Long) {
        val session = externalBus.beginDocument(generation, config.dashboardNativeKiosk)
        this.session = session
        v2Handshake.begin(session)
        authQueue.clear()
        v2BridgeDocument = V2BridgeDocument(config, generation, session, host.entityFilterLease())
    }

    /** WebView creation failed after [openDocumentForCreate]. */
    fun abandonCreate() {
        externalBus.invalidate()
        session = null
        v2BridgeDocument = null
        v2ListenerView = null
    }

    fun expectPageStart(url: String) {
        expectedPageStartUrl = url
    }

    /** The URL the app itself last loaded, cleared as it is read by the page-start callback. */
    fun consumeExpectedPageStart(): String? = expectedPageStartUrl.also { expectedPageStartUrl = null }

    /** Local recovery documents never need credentials or the external bus. */
    fun suspend(view: WebView) {
        clearBusTimeouts()
        authQueue.clear()
        externalBus.invalidate()
        session = null
        v2BridgeDocument = null
        removeListeners(view)
    }

    /** The renderer is being torn down; [removeListeners] follows for the view being destroyed. */
    fun teardown() {
        externalBus.invalidate()
        session = null
        v2BridgeDocument = null
        authQueue.clear()
        v2Handshake.reset()
        expectedPageStartUrl = null
        clearBusTimeouts()
    }

    /** The renderer process died under the current WebView. */
    fun invalidateForCrash() {
        externalBus.invalidate()
        session = null
        v2BridgeDocument = null
        v2ListenerView = null
        authQueue.clear()
        v2Handshake.reset()
        clearBusTimeouts()
    }

    fun resetHandshake() = v2Handshake.reset()

    /** The handshake watchdog fired: true when the missing V2 envelope has used up its reload budget. */
    fun handshakeTimedOut(): Boolean {
        val session = session
        return session != null && v2Handshake.onTimeout(session)
    }

    fun commitDocument(generation: Long) {
        session?.takeIf { current(generation, it) }?.let(v2Handshake::commit)
    }

    fun finishDocument(generation: Long) {
        session?.takeIf { current(generation, it) }?.let(v2Handshake::finish)
    }

    fun navigate(path: String) {
        val session = session ?: return
        dispatch(externalBus.navigate(session, path))
    }

    fun updateKioskPreference(kiosk: Boolean) {
        session?.let { session -> dispatch(externalBus.updateKioskPreference(session, kiosk)) }
    }

    fun connected(session: ExternalBusController.Session) = dispatch(externalBus.onConnection(session, true))

    fun disconnected(session: ExternalBusController.Session) {
        externalBus.onConnection(session, false)
    }

    fun removeListeners(view: WebView) {
        // Explicitly remove the legacy object as well: a V2-only renderer never leaves a V1 interface
        // installed, including across WebView reuse or a provider restoring internal state.
        runCatching { view.removeJavascriptInterface("externalApp") }
        if (!webViewFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching { WebViewCompat.removeWebMessageListener(view, EXTERNAL_APP_V2) }
        runCatching { WebViewCompat.removeWebMessageListener(view, HaPaneldV2Protocol.OBJECT_NAME) }
        if (v2ListenerView === view) v2ListenerView = null
    }

    fun installListeners(
        view: WebView,
        config: Config,
    ) {
        check(webViewFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val allowedOrigins = dashboardDocumentStartOrigins(config.haEffectiveUrl, allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config))
        view.removeJavascriptInterface("externalApp")
        // Installation happens only before an HA navigation, never during an ordinary reload. Remove
        // defensively so a provider-restored name cannot make add fail or retain an unknown callback.
        runCatching { WebViewCompat.removeWebMessageListener(view, EXTERNAL_APP_V2) }
        runCatching { WebViewCompat.removeWebMessageListener(view, HaPaneldV2Protocol.OBJECT_NAME) }
        WebViewCompat.addWebMessageListener(view, EXTERNAL_APP_V2, allowedOrigins) {
                callbackView, message, sourceOrigin, isMainFrame, _ ->
            val document = v2BridgeDocument ?: return@addWebMessageListener
            if (!v2CallbackCurrent(callbackView, document, sourceOrigin.toString(), isMainFrame)) {
                return@addWebMessageListener
            }
            when (val incoming = ExternalAppV2Protocol.parse(message.data)) {
                is ExternalAppV2Protocol.Incoming.GetExternalAuth -> {
                    if (enqueueV2Auth(document, incoming.payload)) markV2Observed(document.session)
                }
                is ExternalAppV2Protocol.Incoming.RevokeExternalAuth -> {
                    val reply = ExternalAuthProtocol.revokeReply(incoming.payload)
                    if (reply != null) markV2Observed(document.session)
                    evaluateBridgeReply(reply, document.generation, document.session)
                }
                is ExternalAppV2Protocol.Incoming.ExternalBus -> {
                    val bus = ExternalBusProtocol.parse(incoming.payload)
                    if (bus !is ExternalBusProtocol.Incoming.Malformed) markV2Observed(document.session)
                    handleExternalBus(bus, document.generation, document.session)
                }
                is ExternalAppV2Protocol.Incoming.Malformed ->
                    Log.w(TAG, "ignored malformed externalAppV2 envelope (${incoming.reason})")
                is ExternalAppV2Protocol.Incoming.Unknown -> Unit
            }
        }
        try {
            WebViewCompat.addWebMessageListener(view, HaPaneldV2Protocol.OBJECT_NAME, allowedOrigins) {
                    callbackView, message, sourceOrigin, isMainFrame, _ ->
                val document = v2BridgeDocument ?: return@addWebMessageListener
                if (!v2CallbackCurrent(callbackView, document, sourceOrigin.toString(), isMainFrame)) {
                    return@addWebMessageListener
                }
                handleHaPaneldV2(
                    HaPaneldV2Protocol.parse(message.data),
                    document.config,
                    document.generation,
                    document.session,
                    document.filterLease,
                )
            }
            v2ListenerView = view
        } catch (error: Throwable) {
            runCatching { WebViewCompat.removeWebMessageListener(view, EXTERNAL_APP_V2) }
            throw error
        }
    }

    private fun markV2Observed(session: ExternalBusController.Session) {
        if (!externalBus.owns(session)) return
        v2Handshake.observe(session)
    }

    private fun v2CallbackCurrent(
        callbackView: WebView,
        document: V2BridgeDocument,
        sourceOrigin: String?,
        isMainFrame: Boolean,
    ): Boolean = v2BridgeDocument === document && isMainFrame &&
        current(document.generation, document.session) && host.currentWeb() === callbackView &&
        sameDashboardOrigin(sourceOrigin, callbackView.url)

    private fun enqueueV2Auth(
        document: V2BridgeDocument,
        payload: String,
    ): Boolean {
        if (v2BridgeDocument !== document || !current(document.generation, document.session)) return false
        val force = ExternalAuthProtocol.validAuthRequestForce(payload) ?: return false
        val request = PendingV2Auth(document.config, document.generation, document.session, payload, force)
        authQueue.offer(request)?.let(::resolveV2Auth)
        return true
    }

    private fun resolveV2Auth(work: BoundedAuthQueue.Work<PendingV2Auth>) {
        val request = work.request
        scope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        HaCredentialManager.resolve(
                            request.config,
                            force = request.force,
                            stillCurrent = { current(request.generation, request.session) },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "external auth resolution failed transiently: ${error.javaClass.simpleName}")
                    DashboardAuth.Result(null)
                }
                if (result.rejected && current(request.generation, request.session)) {
                    host.onAuthRejected(request.generation)
                }
                evaluateBridgeReply(
                    ExternalAuthProtocol.authReply(
                        request.payload,
                        result.session?.accessToken,
                        result.session?.expiresInSec ?: 0L,
                    ),
                    request.generation,
                    request.session,
                )
            } finally {
                authQueue.complete(work) { next ->
                    current(next.generation, next.session)
                }?.let(::resolveV2Auth)
            }
        }
    }

    private fun evaluateBridgeReply(
        script: String?,
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (script != null && current(generation, session)) host.currentWeb()?.evaluateJavascript(script, null)
    }

    private fun handleHaPaneldV2(
        incoming: HaPaneldV2Protocol.Incoming,
        config: Config,
        generation: Long,
        session: ExternalBusController.Session,
        filterLease: EntityFilterTelemetry.Lease?,
    ) {
        if (!current(generation, session)) return
        when (incoming) {
            HaPaneldV2Protocol.Incoming.EntityFilterSubscriptionModified -> {
                val lease = filterLease ?: return
                if (config.dashboardEntityFilterEnabled) EntityFilterTelemetry.subscriptionModified(lease)
            }
            is HaPaneldV2Protocol.Incoming.EntityFilterTrafficMetrics -> {
                val lease = filterLease ?: return
                if (incoming.payload.length > 512) return
                runCatching { EntityFilterProtocol.parseTrafficBatch(incoming.payload) }
                    .onSuccess { EntityFilterTelemetry.traffic(lease, it) }
            }
            is HaPaneldV2Protocol.Incoming.EntityLearningAccesses -> {
                if (!config.dashboardEntityLearningEnabled) return
                if (incoming.payload.length < EntityLearningProtocol.MAX_NATIVE_BRIDGE_PAYLOAD_CHARS) {
                    EntityLearningRuntime.recordAccessBatch(incoming.payload)
                } else FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_BROWSER_OBSERVER)
            }
            is HaPaneldV2Protocol.Incoming.EntityLearningMetrics -> {
                if (!config.dashboardEntityLearningEnabled) return
                if (incoming.payload.length < EntityLearningProtocol.MAX_NATIVE_BRIDGE_PAYLOAD_CHARS) {
                    EntityLearningRuntime.recordMetricBatch(incoming.payload)
                } else FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_BROWSER_OBSERVER)
            }
            is HaPaneldV2Protocol.Incoming.Malformed ->
                Log.w(TAG, "ignored malformed haPaneldV2 envelope (${incoming.reason})")
            is HaPaneldV2Protocol.Incoming.Unknown -> Unit
        }
    }

    private fun clearBusTimeouts() {
        busTimeouts.values.forEach(main::removeCallbacks)
        busTimeouts.clear()
    }

    /** The only app→frontend external-bus evaluation site. Commands are produced by the typed
     * controller, correlated to this document, and given a one-shot timeout callback. */
    private fun dispatch(command: ExternalBusController.Outbound?) {
        command ?: return
        if (!externalBus.owns(command.session) || !host.rendererCurrent(command.session.rendererGeneration)) return
        val view = host.currentWeb() ?: return
        command.evictedIds.forEach { id -> busTimeouts.remove(id)?.let(main::removeCallbacks) }
        val timeout = Runnable {
            busTimeouts.remove(command.id)
            handleBusCompletion(command.session, externalBus.onTimeout(command.session, command.id))
        }
        busTimeouts[command.id] = timeout
        view.evaluateJavascript(command.script, null)
        main.postDelayed(timeout, externalBus.commandTimeoutMs)
    }

    private fun handleBusCompletion(
        session: ExternalBusController.Session,
        completion: ExternalBusController.Completion,
    ) {
        if (!completion.matched || !externalBus.owns(session)) return
        completion.id?.let { id -> busTimeouts.remove(id)?.let(main::removeCallbacks) }
        val kind = when (completion.kind) {
            is ExternalBusController.CommandKind.Navigate -> "navigate"
            is ExternalBusController.CommandKind.KioskMode -> "kiosk_mode/set"
            null -> "unknown"
        }
        val error = completion.error?.let { " error=${it.code.orEmpty()}:${it.message.orEmpty()}" }.orEmpty()
        Log.d(TAG, "bus result command=$kind success=${completion.success}$error")
        dispatch(completion.followUp)
        if (completion.retryKiosk) {
            main.postDelayed(
                { if (externalBus.owns(session)) dispatch(externalBus.retryKiosk(session)) },
                externalBus.kioskRetryDelayMs,
            )
        }
    }

    private fun handleExternalBus(
        incoming: ExternalBusProtocol.Incoming,
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (!current(generation, session)) return
        when (incoming) {
            is ExternalBusProtocol.Incoming.ConfigGet ->
                host.currentWeb()?.evaluateJavascript(
                    ExternalBusProtocol.configResult(incoming.id, appVersion()),
                    null,
                )
            ExternalBusProtocol.Incoming.ConfigScreenShow -> runCatching { host.openConfigScreen() }
            is ExternalBusProtocol.Incoming.ConnectionStatus ->
                host.onConnectionStatus(incoming.event, generation, session)
            ExternalBusProtocol.Incoming.FrontendLoaded ->
                dispatch(externalBus.onFrontendLoaded(session))
            ExternalBusProtocol.Incoming.ThemeUpdate -> host.onThemeUpdate(generation, session)
            is ExternalBusProtocol.Incoming.Result ->
                handleBusCompletion(session, externalBus.onResult(session, incoming))
            is ExternalBusProtocol.Incoming.Malformed ->
                Log.w(TAG, "ignored malformed external-bus message (${incoming.reason})")
            is ExternalBusProtocol.Incoming.Unknown -> Unit
        }
    }

    private companion object {
        const val TAG = DashboardActivity.TAG
        const val V2_MISSING_RELOAD_LIMIT = 2           // live-compatible HA but no V2 envelope after one retry
        const val EXTERNAL_APP_V2 = "externalAppV2"
    }
}
