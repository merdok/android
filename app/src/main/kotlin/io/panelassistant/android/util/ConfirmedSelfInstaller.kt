package io.panelassistant.android.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import io.panelassistant.android.migration.MigrationTokenReceiver
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The rootless self-update route: Android's own package installer, confirmed by one tap on the panel.
 *
 * A panel with neither root nor the helper (a Meta Portal, which also loses ADB at every restart) can
 * still replace its own app when it holds the `REQUEST_INSTALL_PACKAGES` app-op, which Panel Assistant
 * grants at install. Android then shows its install confirmation and the person at the panel taps
 * Install. This route only ever carries the running app's own package, after [AppInstaller] has applied
 * the release-signer pin, the update policy and the database admission, so it is not a way to install
 * arbitrary uploaded APKs: those stay root- or helper-only.
 */
internal object ConfirmedSelfInstaller {
    private const val TAG = "ha-paneld/install"
    private const val ACTION_PREFIX = "io.panelassistant.android.CONFIRMED_INSTALL."

    /** How long the confirmation may wait for a tap before the session is abandoned. */
    internal const val CONFIRMATION_TIMEOUT_MS = 5L * 60L * 1000L

    /** Whether this panel may ask Android to install its own update with the user's confirmation. */
    fun available(context: Context): Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** One report from the package installer: [status] is a `PackageInstaller.STATUS_*` value. */
    internal data class SessionReport(val status: Int, val message: String?, val confirmation: Intent? = null)

    /** What a report means for the waiting install. */
    internal sealed interface Step {
        /** Android asks the person at the panel; show its confirmation and keep waiting. */
        data class Confirm(val confirmation: Intent?) : Step
        data class Done(val outcome: InstallOutcome) : Step
    }

    internal fun classify(report: SessionReport): Step = when (report.status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> Step.Confirm(report.confirmation)
        PackageInstaller.STATUS_SUCCESS -> Step.Done(InstallOutcome.Succeeded)
        // Android reports both a Cancel tap and a package verifier's rejection as aborted (a Meta
        // Portal's verifier refuses every app not signed by Meta), so the message names both.
        PackageInstaller.STATUS_FAILURE_ABORTED -> Step.Done(
            InstallOutcome.Retryable(
                "update not installed: cancelled on the panel or refused by its package verifier" +
                    report.message?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
            ),
        )
        PackageInstaller.STATUS_FAILURE_STORAGE -> Step.Done(
            InstallOutcome.Retryable("install failed: insufficient storage (${report.message.orEmpty()})"),
        )
        PackageInstaller.STATUS_FAILURE_BLOCKED -> Step.Done(
            InstallOutcome.Retryable("install blocked on the panel (${report.message.orEmpty()})"),
        )
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        PackageInstaller.STATUS_FAILURE_INVALID -> Step.Done(
            InstallOutcome.Rejected("install failed (${report.message.orEmpty()})"),
        )
        else -> Step.Done(InstallOutcome.Retryable("install failed (${report.message.orEmpty()})"))
    }

    /**
     * Follow one committed session to its end. A confirmation request is shown through [showConfirmation]
     * and announced through [onAwaitingConfirmation] once; a session nobody confirms within [timeoutMs]
     * ends as retryable. On success Android usually replaces this process before the report arrives.
     * A reported success counts only when [successConfirmed] sees the new version installed, so a report
     * that did not come from a finished install can never hold the caller's success-only state.
     */
    internal suspend fun follow(
        reports: ReceiveChannel<SessionReport>,
        showConfirmation: (Intent?) -> Boolean,
        onAwaitingConfirmation: () -> Unit,
        successConfirmed: () -> Boolean,
        timeoutMs: Long = CONFIRMATION_TIMEOUT_MS,
    ): InstallOutcome = withTimeoutOrNull(timeoutMs) {
        var announced = false
        for (report in reports) {
            when (val step = classify(report)) {
                is Step.Done -> return@withTimeoutOrNull if (
                    step.outcome == InstallOutcome.Succeeded && !runCatching(successConfirmed).getOrDefault(false)
                ) {
                    InstallOutcome.Retryable("install reported success but the new version is not installed")
                } else {
                    step.outcome
                }
                is Step.Confirm -> {
                    if (!showConfirmation(step.confirmation)) {
                        return@withTimeoutOrNull InstallOutcome.Retryable(
                            "update not confirmed: the panel could not show Android's install confirmation",
                        )
                    }
                    if (!announced) {
                        announced = true
                        onAwaitingConfirmation()
                    }
                }
            }
        }
        InstallOutcome.Retryable("install failed: the package installer stopped reporting")
    } ?: InstallOutcome.Retryable("update not confirmed on the panel in time")

    /**
     * Only the app's own status PendingIntent may report on a session. Android sends that broadcast with
     * this app's identity, which holds [STATUS_BROADCAST_PERMISSION]; another app (or the shell) does not,
     * so it cannot forge a success or a confirmation for a session id it can see. Android 13 and later
     * also refuse every outside sender to a non-exported receiver.
     */
    private fun registerStatusReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, STATUS_BROADCAST_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, STATUS_BROADCAST_PERMISSION, null)
        }
    }

    /** The release-signer-only permission the two panel-app identities already declare and hold. */
    internal const val STATUS_BROADCAST_PERMISSION = MigrationTokenReceiver.PERMISSION

    /**
     * A confirmation is shown only when it opens a system app (Android's package installer), never an
     * arbitrary activity named in a report. [resolvedAppFlags] are the `ApplicationInfo.flags` of the app
     * the Intent resolves to, or null when it resolves to nothing.
     */
    internal fun mayShowConfirmation(resolvedAppFlags: Int?): Boolean =
        resolvedAppFlags != null && resolvedAppFlags and ApplicationInfo.FLAG_SYSTEM != 0

    /**
     * The installer showing this app's own update confirmation, while it waits, so the app's accessibility
     * service may press Install for it (a rootless panel with nobody standing at it, such as a Shelly Wall
     * Display whose vendor app is device owner). Set only between showing the confirmation and the session's
     * end, and only for the system installer the confirmation resolved to; cleared once pressed.
     */
    @Volatile
    private var confirmationInstaller: String? = null

    /** The installer package whose confirmation of this app's own update is waiting to be pressed, or null. */
    fun awaitingConfirmationIn(): String? = confirmationInstaller

    /** The confirmation shown by [installer] was pressed: press nothing more for this session. */
    fun confirmationPressed(installer: String) {
        if (confirmationInstaller == installer) confirmationInstaller = null
    }

    /** Whether the installed copy of the running package is now at least [versionCode]. */
    internal fun installedAtLeast(installedVersionCode: Long?, versionCode: Long): Boolean =
        installedVersionCode != null && installedVersionCode >= versionCode

    /** Write [apk] into a new installer session for the running package, commit it, and follow it. */
    suspend fun install(
        context: Context,
        apk: File,
        expectedVersionCode: Long,
        submit: (() -> Unit) -> InstallOutcome.Failure?,
        onAwaitingConfirmation: () -> Unit,
    ): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = runCatching { installer.createSession(params) }.getOrElse {
            return InstallOutcome.Retryable("install failed: could not open an installer session (${it.message})")
        }
        val action = ACTION_PREFIX + sessionId
        val reports = Channel<SessionReport>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                reports.trySend(
                    SessionReport(
                        status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                        message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT),
                    ),
                )
            }
        }
        registerStatusReceiver(context, receiver, IntentFilter(action))
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // The status intent must stay mutable: the installer fills in its status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(
                    context, sessionId, Intent(action).setPackage(context.packageName), flags,
                )
                submit {
                    session.commit(status.intentSender)
                    committed = true
                }?.let { refusal ->
                    runCatching { installer.abandonSession(sessionId) }
                    return refusal
                }
            }
            val outcome = follow(
                reports,
                showConfirmation = { confirmation ->
                    confirmation != null && runCatching {
                        val target = confirmation.resolveActivityInfo(context.packageManager, 0)?.applicationInfo
                        if (!mayShowConfirmation(target?.flags)) return@runCatching false
                        confirmationInstaller = target?.packageName
                        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                },
                onAwaitingConfirmation = onAwaitingConfirmation,
                successConfirmed = {
                    @Suppress("DEPRECATION")
                    val installed = context.packageManager.getPackageInfo(context.packageName, 0).let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
                    }
                    installedAtLeast(installed, expectedVersionCode)
                },
            )
            if (outcome is InstallOutcome.Failure) Log.w(TAG, "confirmed install: ${outcome.message}")
            if (outcome !is InstallOutcome.Succeeded) runCatching { installer.abandonSession(sessionId) }
            return outcome
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            return InstallOutcome.Retryable(
                "install failed: ${e.message ?: e.javaClass.simpleName}",
                mayHaveCommitted = committed,
            )
        } finally {
            confirmationInstaller = null
            runCatching { context.unregisterReceiver(receiver) }
            reports.close()
        }
    }
}
