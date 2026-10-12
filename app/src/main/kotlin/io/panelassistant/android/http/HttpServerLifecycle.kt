package io.panelassistant.android.http

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
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
    private val module: Application.() -> Unit,
) {
    private var stopEngine: (() -> Unit)? = null
    private var boundHardened: Boolean? = null

    /** Bind now; a failure releases the engine, runs [closeIngress] and propagates. */
    @Synchronized
    fun start(closeIngress: () -> Unit) {
        val mode = hardened()
        // Stored as a stop lambda over a type-inferred local, so Ktor's EmbeddedServer generic type (which
        // shifts between Ktor versions) is never named.
        val server = scope.embeddedServer(CIO, port = port(), host = listenerHost(mode)) { module() }
        startOwnedHttpServer(
            start = { server.start(wait = false) },
            stop = { server.stop(500, 1500) },
            closeIngress = closeIngress,
        )
        stopEngine = { server.stop(500, 1500) }
        boundHardened = mode
    }

    /** Bind again when the security mode moved; true when it did. Nothing happens once [stop] has run. */
    @Synchronized
    fun rebind(): Boolean {
        val stop = stopEngine ?: return false
        if (boundHardened == hardened()) return false
        stop()
        stopEngine = null
        // The old socket may hold the port briefly after it closes; without a listener the panel's own WebView
        // cannot sign in, so a failed bind is retried a few times before it is reported.
        var attempt = 0
        while (true) {
            try {
                start(closeIngress = {})
                return true
            } catch (failure: Exception) {
                if (++attempt >= REBIND_ATTEMPTS) throw failure
                Thread.sleep(REBIND_RETRY_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        stopEngine?.invoke()
        stopEngine = null
    }

    private companion object {
        const val REBIND_ATTEMPTS = 5
        const val REBIND_RETRY_MS = 500L
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
