package io.panelassistant.android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

// The service's pure startup, handoff, teardown and recovery rules, kept apart from its Android lifecycle.

internal enum class ServiceStartupDisposition {
    RUNNING,
    PROFILE_ACTIVATION_ROLLBACK,
    DEGRADED,
}

internal const val INSTALLED_SUCCESSOR_HANDOFF_ACTION =
    "io.github.maxlyth.hapaneld.action.HANDOFF_INSTALLED_SUCCESSOR"

internal fun installedHandoffWakeRequested(isBridge: Boolean, action: String?): Boolean =
    isBridge && action == INSTALLED_SUCCESSOR_HANDOFF_ACTION

/** Retains only an explicit host handoff wake until this service's startup is healthy. */
internal class InstalledSuccessorHandoffWake {
    private var requested = false
    private var healthy = false

    @Synchronized fun request(): Boolean {
        requested = true
        return consumeIfHealthy()
    }

    @Synchronized fun markStartupHealthy(): Boolean {
        healthy = true
        return consumeIfHealthy()
    }

    private fun consumeIfHealthy(): Boolean {
        if (!healthy || !requested) return false
        requested = false
        return true
    }
}

internal data class StartupRecoveryDecision(val restart: Boolean, val nextAttempt: Int)

internal fun shouldForceFreshProcessAfterExternalRecovery(
    attempt: Int,
    maxAttempts: Int,
    kioskSafe: Boolean,
    navbarSafe: Boolean,
    screenSafe: Boolean,
    relaySafe: Boolean,
): Boolean = attempt >= maxAttempts && screenSafe && !(kioskSafe && navbarSafe && relaySafe)

/** Main-safe phase zero for both Android destruction and explicit process replacement. */
internal fun beginAudioTeardown(
    closeAdmission: () -> Unit,
    cancelCurrent: () -> Unit,
) {
    closeAdmission()
    cancelCurrent()
}

/** Continue the complete owner sweep while retaining any ambiguous cleanup result for the final gate. */
internal class ServiceOwnerCleanupTracker {
    private val complete = AtomicBoolean(true)

    fun run(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        complete.set(false)
        error
    }

    fun record(result: Boolean) {
        if (!result) complete.set(false)
    }

    fun isComplete(): Boolean = complete.get()
}

/** Reuse the onDestroy screen owner; a second mutator could wake a successor after barrier release. */
internal fun proveScreenSafeForBoundary(
    existingOwner: CompletableFuture<Boolean>,
): Boolean = existingOwner.getNow(false)

/** Retry pending durable kiosk cleanup after the boot escape window without blocking unrelated service
 * startup. The retry count is finite so permanently unavailable root never creates steady background
 * load; retained recovery markers make the next service start retry again. [recover] and [enable] answer
 * null when they did not run (the command worker was being replaced): that waits [retryDelayMs] and asks
 * again without using an attempt, until [shouldContinue] says stop or the thread is interrupted. */
internal fun recoverAndMaybeEnableKiosk(
    escapeDelayMs: Long,
    retryDelayMs: Long,
    maxAttempts: Int,
    shouldContinue: () -> Boolean,
    recover: () -> Boolean?,
    enabled: () -> Boolean,
    enable: () -> Boolean?,
    pause: (Long) -> Unit = Thread::sleep,
): Boolean {
    require(escapeDelayMs >= 0L)
    require(retryDelayMs >= 0L)
    require(maxAttempts > 0)
    fun ran(step: () -> Boolean?): Boolean? {
        while (shouldContinue()) {
            step()?.let { return it }
            pause(retryDelayMs)
        }
        return null
    }
    return try {
        pause(escapeDelayMs)
        repeat(maxAttempts) { attempt ->
            when (ran(recover)) {
                null -> return false
                true -> return shouldContinue() && (!enabled() || ran(enable) == true)
                false -> if (attempt + 1 < maxAttempts) pause(retryDelayMs)
            }
        }
        false
    } catch (_: InterruptedException) {
        false
    }
}

/** Bound process-level recovery for transient startup failures; clock rollback/reboot opens a new window. */
internal fun startupRecoveryDecision(
    previousAttempts: Int,
    previousAtMs: Long,
    nowMs: Long,
    maxAttempts: Int = 3,
    windowMs: Long = 10L * 60L * 1_000L,
): StartupRecoveryDecision {
    val sameWindow = previousAtMs in 1..nowMs && nowMs - previousAtMs <= windowMs
    val next = (if (sameWindow) previousAttempts.coerceAtLeast(0) else 0) + 1
    return StartupRecoveryDecision(next <= maxAttempts, next)
}

internal fun awaitServiceStartup(
    startup: Future<Boolean>,
    profileActivationGeneration: Long?,
): ServiceStartupDisposition {
    val healthy = runCatching { startup.get() }.getOrDefault(false)
    return when {
        healthy -> ServiceStartupDisposition.RUNNING
        profileActivationGeneration != null -> ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK
        else -> ServiceStartupDisposition.DEGRADED
    }
}
