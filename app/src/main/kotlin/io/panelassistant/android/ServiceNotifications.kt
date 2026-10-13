package io.panelassistant.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot

/**
 * What [PaneldService] shows in the notification shade: the ongoing notification that keeps it a
 * foreground service, with the microphone foreground-service type while a lease holds it, and the
 * storage-health notice.
 *
 * It also knows which service is foreground in this process, which [PaneldService.start] needs: a
 * service that has promoted is never asked to promote again (see [dispatchPanelServiceStart]).
 */
internal class ServiceNotifications(private val service: Service) {
    // The text of the notification currently on screen, so re-issuing startForeground to change the
    // claimed service types does not silently rewrite what the panel is telling its owner.
    @Volatile private var foregroundStatusText = "Starting…"
    // Whether the microphone foreground-service type is currently claimed. Only ever true while
    // something holds a microphone lease.
    @Volatile private var microphoneForeground = false

    private fun notificationChannel(silent: Boolean): Pair<NotificationManager, String> {
        val mgr = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (silent) SILENT_CHANNEL_ID else CHANNEL_ID
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "Panel Assistant", NotificationManager.IMPORTANCE_MIN).apply {
                description = service.nativeString(R.string.panel_agent_channel_description)
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            },
        )
        return mgr to channelId
    }

    fun startForegroundCompat(statusText: String, silent: Boolean) {
        foregroundStatusText = statusText
        val (_, channelId) = notificationChannel(silent)
        promoteToForeground(foregroundNotification(channelId, silent, statusText), microphoneForeground)
        Log.i(TAG, "foreground service started${promotionLatencySuffix()}")
    }

    /**
     * How long this process took to reach `startForeground`, when the platform can tell us.
     *
     * Android gives a `startForegroundService` caller a few seconds to promote, and on an
     * out-of-process cold start that budget covers process creation and `Application.onCreate` as well
     * as everything above. Missing it kills the process with `RemoteServiceException`, which is what
     * slower Android 8.1 panels were doing. An absence of crashes cannot prove that fixed, so the
     * margin is measured on every start instead of inferred: this is the instrument that says how much
     * headroom the slowest panel actually has.
     *
     * `Process.getStartElapsedRealtime` is API 24+, and minSdk is 26, so it is always available; it is
     * still defensive because a wrong answer here must never be the thing that fails a promote.
     */
    private fun promotionLatencySuffix(): String = runCatching {
        " (promoted ${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} ms after process start)"
    }.getOrDefault("")

    /**
     * Claim exactly the foreground-service types this service is entitled to at this moment.
     *
     * The manifest declares `specialUse|microphone`, because a type can only be claimed if it is
     * declared, but an idle panel must not be a microphone foreground service. The two-argument
     * `startForeground` claims every type the manifest declares, so from Android 10 — where naming
     * the set became possible — the set is named explicitly: `specialUse` on Android 14 and later,
     * where that type exists, plus `microphone` on Android 11 and later, where *that* type exists,
     * and then only while something holds a lease. Android 9 and earlier ignore the attribute
     * entirely, and Android 10 has no microphone type to claim.
     */
    private fun promoteToForeground(notification: Notification, microphone: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            service.startForeground(NOTIF_ID, notification)
            foregroundService = service
            return
        }
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        service.startForeground(NOTIF_ID, notification, types)
        foregroundService = service
    }

    /** Called first thing in onDestroy: from here on a start request must promote a new generation. */
    fun releaseForeground() {
        if (foregroundService === service) foregroundService = null
    }

    /** Add or drop the microphone type; the caller has already refused a stopping service. */
    fun updateMicrophoneForeground(active: Boolean, silent: Boolean): Boolean {
        if (microphoneForeground == active) return true
        val (_, channelId) = notificationChannel(silent)
        return runCatching {
            promoteToForeground(foregroundNotification(channelId, silent, foregroundStatusText), active)
            microphoneForeground = active
        }.onFailure {
            Log.w(TAG, "could not ${if (active) "claim" else "release"} the microphone foreground-service type", it)
        }.isSuccess
    }

    fun updateForegroundStatus(statusText: String, silent: Boolean) {
        foregroundStatusText = statusText
        val (mgr, channelId) = notificationChannel(silent)
        mgr.notify(NOTIF_ID, foregroundNotification(channelId, silent, statusText))
    }

    /** Rewrite the ongoing notification without recording it, for a generation that is standing down. */
    fun describeStandingDown(statusText: String) {
        val (mgr, channelId) = notificationChannel(silent = true)
        runCatching {
            mgr.notify(NOTIF_ID, foregroundNotification(channelId, silent = true, statusText))
        }
            .onFailure { Log.w(TAG, "could not describe the stood-down service generation", it) }
    }

    private fun foregroundNotification(channelId: String, silent: Boolean, statusText: String): Notification =
        NotificationCompat.Builder(service, channelId)
            .setContentTitle(service.nativeString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(silent)
            .build()

    fun updateStorageHealthNotification(snapshot: StorageHealthSnapshot) {
        when (val decision = storageHealthNotificationDecision(snapshot)) {
            StorageHealthNotificationDecision.KeepExisting -> Unit
            StorageHealthNotificationDecision.Cancel -> runCatching {
                (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(STORAGE_HEALTH_NOTIF_ID)
            }.onFailure { Log.w(TAG, "could not clear storage health notification", it) }
            is StorageHealthNotificationDecision.Show -> runCatching {
                val mgr = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.createNotificationChannel(
                    NotificationChannel(
                        STORAGE_HEALTH_CHANNEL_ID,
                        service.nativeString(R.string.storage_health_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = service.nativeString(R.string.storage_health_channel_description)
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
                val storageHealthDestination = Intent()
                storageHealthDestination.setClass(service, ConfigActivity::class.java)
                storageHealthDestination
                    .putExtra("path", "/")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val contentIntent = PendingIntent.getActivity(
                    service,
                    STORAGE_HEALTH_NOTIF_ID,
                    storageHealthDestination,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val (title, body) = localizedStorageHealthNotification(snapshot)
                val notification = NotificationCompat.Builder(service, STORAGE_HEALTH_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setContentIntent(contentIntent)
                    .build()
                mgr.notify(STORAGE_HEALTH_NOTIF_ID, notification)
            }.onFailure { Log.w(TAG, "could not show storage health notification", it) }
        }
    }

    private fun localizedStorageHealthNotification(snapshot: StorageHealthSnapshot): Pair<String, String> {
        if (snapshot.severity == StorageHealthSeverity.CRITICAL) {
            val capacity = if (snapshot.totalBytes > 0L) {
                service.nativeString(
                    R.string.storage_capacity_suffix,
                    snapshot.usableBytes.coerceAtLeast(0L) / (1024L * 1024L),
                )
            } else ""
            return service.nativeString(R.string.storage_pressure_title) to
                service.nativeString(R.string.storage_pressure_body, capacity)
        }
        val during = snapshot.databaseFailureOperationLabel
            ?.let { service.nativeString(R.string.database_during_suffix, it) }
            .orEmpty()
        val body = when (snapshot.databaseFailureKind) {
            StorageDatabaseFailureKind.STORAGE_FULL -> R.string.database_full_body
            StorageDatabaseFailureKind.IO -> R.string.database_io_body
            StorageDatabaseFailureKind.CORRUPTION -> R.string.database_corruption_body
            StorageDatabaseFailureKind.BUSY -> R.string.database_busy_body
            StorageDatabaseFailureKind.UNKNOWN, null -> R.string.database_failure_body
        }
        return service.nativeString(R.string.database_attention_title) to service.nativeString(body, during)
    }

    companion object {
        private const val TAG = "ha-paneld/svc"
        private const val CHANNEL_ID = "ha-paneld"
        // Channel sound is immutable after creation. A distinct ID guarantees the silence setting can
        // supersede an older install whose original channel was created with a notification sound.
        private const val SILENT_CHANNEL_ID = "ha-paneld-silent-v1"
        private const val STORAGE_HEALTH_CHANNEL_ID = "ha-paneld-storage-health-v1"
        private const val NOTIF_ID = 1
        private const val STORAGE_HEALTH_NOTIF_ID = 2

        // The service that has called startForeground and is not yet being destroyed. A start request
        // it receives must be ordinary: on Android 8.x a startForegroundService that follows a
        // foreground start, an ordinary start and the promotion lands on the first start's still-armed
        // timer, which then stops the service and crashes the process.
        @Volatile private var foregroundService: Service? = null

        val anyServiceForeground: Boolean get() = foregroundService != null
    }
}
