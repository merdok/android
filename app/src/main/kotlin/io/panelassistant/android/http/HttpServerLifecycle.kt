package io.panelassistant.android.http

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/**
 * The address the `:8888` listener binds (protocol section 20.7): loopback only when [offLan] (hardened mode
 * with the REST routes closed, section 20.8), where the panel's own WebView still signs in through it, else
 * the IPv6 wildcard, which on Android is dual-stack (net.ipv6.bindv6only=0) and so answers IPv4 too.
 */
internal fun listenerHost(offLan: Boolean): String = if (offLan) "127.0.0.1" else "::"

/** Whether the listener is off the LAN: hardened mode with the REST routes closed (sections 20.7 and 20.8). */
internal fun listenerOffLan(config: io.panelassistant.android.Config): Boolean =
    config.hardenedSecurityEnabled && config.uiRoutesClosed

/**
 * The listener's engine, one generation at a time, bound for whether it was off the LAN when started.
 * [rebind] replaces the engine when that has moved since, so hardened mode with the routes closed takes the
 * stub off the LAN without restarting the service.
 */
internal class PaneldListener(
    private val scope: CoroutineScope,
    private val port: () -> Int,
    private val hardened: () -> Boolean,
    /** A bind that failed (thrown, or raised inside the engine); a rebind keeps trying. */
    private val onBindFailure: (Throwable) -> Unit = {},
    private val retryInitialMs: Long = 500,
    private val retryMaxMs: Long = 30_000,
    private val module: Application.() -> Unit,
) {
    private var stopEngine: (() -> Unit)? = null
    /** The mode the engine is bound for; null while no engine is bound. */
    private var boundHardened: Boolean? = null
    private var started = false
    @Volatile private var stopped = false

    /** Bind now; a failure releases the engine, runs [closeIngress] and propagates. */
    @Synchronized
    fun start(closeIngress: () -> Unit) {
        bind(closeIngress)
        started = true
    }

    private fun bind(closeIngress: () -> Unit) {
        val mode = hardened()
        // Stored as a stop lambda over a type-inferred local, so Ktor's EmbeddedServer generic type (which
        // shifts between Ktor versions) is never named.
        // CIO also raises a failed bind inside the engine's own coroutines: report it here rather than let it
        // reach the service scope, which has no handler, as an uncaught exception.
        val engineScope = CoroutineScope(scope.coroutineContext + CoroutineExceptionHandler { _, failure -> onBindFailure(failure) })
        val server = engineScope.embeddedServer(CIO, port = port(), host = listenerHost(mode)) { module() }
        startOwnedHttpServer(
            start = { server.start(wait = false) },
            stop = { server.stop(500, 1500) },
            closeIngress = closeIngress,
        )
        stopEngine = { server.stop(500, 1500) }
        boundHardened = mode
    }

    /**
     * Bind again when the security mode moved; true once bound for the new mode, false when nothing needed
     * doing or [stop] ran first. Without a listener the panel's own WebView cannot sign in, so a bind that fails
     * (the old socket may hold the port for a while) is retried with backoff until it succeeds; meanwhile the
     * listener records itself unbound. Blocks its caller, which runs it off the main thread.
     */
    fun rebind(): Boolean {
        synchronized(this) {
            if (stopped || !started) return false
            if (stopEngine != null && boundHardened == hardened()) return false
            stopEngine?.invoke()
            stopEngine = null
            boundHardened = null
        }
        var waitMs = retryInitialMs
        while (true) {
            synchronized(this) {
                if (stopped) return false
                if (stopEngine != null) return true
                try {
                    bind(closeIngress = {})
                    return true
                } catch (failure: Exception) {
                    onBindFailure(failure)
                }
            }
            Thread.sleep(waitMs)
            waitMs = minOf(waitMs * 2, retryMaxMs)
        }
    }

    /** Whether an engine is bound now. */
    @Synchronized
    fun bound(): Boolean = stopEngine != null

    @Synchronized
    fun stop() {
        stopped = true
        stopEngine?.invoke()
        stopEngine = null
        boundHardened = null
    }
}

/** Start one HTTP-engine generation. A failed start must release any partially acquired engine resources
 * and close request admission without replacing the original failure that the service lifecycle observes. */
internal fun startOwnedHttpServer(
    start: () -> Unit,
    stop: () -> Unit,
    closeIngress: () -> Unit,
) {
    try {
        start()
    } catch (error: Exception) {
        runCatching(stop)
        runCatching(closeIngress)
        throw error
    }
}

/**
 * Do not admit a new best-effort startup probe once HTTP teardown has begun. The volatile admission
 * read is the phase boundary: an already-admitted read-only phase may finish while teardown closes
 * HTTP, then the service scope's cancellation/join owns its bounded drain.
 */
internal fun runPrewarmPhases(
    isStopping: () -> Boolean,
    management: () -> Unit,
    companion: () -> Unit,
) {
    if (isStopping()) return
    management()
    if (isStopping()) return
    companion()
}

/** Close every HTTP-owned admission/resource and retain any ambiguous result while continuing the sweep. */
internal fun stopHttpOwners(
    closeOperationAdmission: () -> Unit,
    closeUploadIngress: () -> Unit,
    stopEngine: () -> Unit,
    stopRelay: () -> Boolean,
    drainTameMutations: () -> Boolean,
    drainRemoteControls: () -> Boolean,
    onIncomplete: (step: String, error: Throwable?) -> Unit,
): Boolean {
    var complete = true
    fun prove(step: String, action: () -> Boolean) {
        val result = runCatching(action)
        if (result.getOrDefault(false)) return
        complete = false
        runCatching { onIncomplete(step, result.exceptionOrNull()) }
    }

    prove("clear-storage admission") { closeOperationAdmission(); true }
    prove("pending uploads") { closeUploadIngress(); true }
    prove("HTTP engine stop request") { stopEngine(); true }
    prove("CDP relay", stopRelay)
    prove("vendor mutation", drainTameMutations)
    prove("remote control", drainRemoteControls)
    return complete
}
