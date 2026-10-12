package io.panelassistant.android.http

import io.panelassistant.android.storage.StorageHealthSnapshot
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Passive diagnostics and status share the existing active-read guard for explicit refreshes. */
internal fun Route.managementRoutes(
    diagnostics: () -> String,
    status: (StorageHealthSnapshot, String?, Boolean) -> String,
    admitActiveRead: suspend (OpCall) -> Boolean,
    refreshUpdates: suspend () -> Unit,
    refreshStorage: suspend () -> StorageHealthSnapshot?,
    cachedStorage: () -> StorageHealthSnapshot,
) = ops {
    get("/diag") {
        call.respondText(
            withContext(Dispatchers.IO) { diagnostics() },
            ContentType.Text.Plain,
        )
    }
    // Health + capabilities as JSON (warnings as ready-to-render HTML) — feeds every
    // variant's Install/health section client-side. ?refresh=1 forces both the GitHub
    // update check and a serialized SQLite observation for this exact response.
    get("/status") {
        val updateRefreshRequested = call.request.queryParameters["refresh"] == "1"
        val homeProofRequested = call.request.queryParameters["home_proof"] == "1"
        val observationNonce = call.request.queryParameters["database_observation_nonce"]
        val refreshRequested = updateRefreshRequested || observationNonce != null
        if (refreshRequested && !admitActiveRead(call)) return@get
        val statusStorage = withContext(Dispatchers.IO) {
            refreshedStatusStorage(
                refreshRequested = refreshRequested,
                refreshUpdates = {
                    if (updateRefreshRequested) runCatching {
                        refreshUpdates()
                    }
                },
                refreshStorage = refreshStorage,
                cachedStorage = cachedStorage,
            )
        }
        call.respondText(
            withContext(Dispatchers.IO) {
                status(
                    statusStorage.snapshot,
                    databaseObservationProof(refreshRequested, observationNonce, statusStorage),
                    homeProofRequested,
                )
            },
            ContentType.Application.Json,
        )
    }
}
