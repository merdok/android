package io.panelassistant.android.http

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.panelassistant.PanelAssistantHelloIdentity
import io.panelassistant.android.panelassistant.PanelAssistantTransportConnection
import io.panelassistant.android.panelassistant.PanelAssistantTransportDemand
import io.panelassistant.android.panelassistant.PanelAssistantTransportOwner
import io.panelassistant.android.sensors.HaApiSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** The real native-transport owner, as the service hands it to the server, against a scripted Home Assistant. */
internal object TestTransportOwners {
    private val credential = HaAuthOwner(url = "https://ha.example", refreshToken = "refresh", clientId = "", staticAccessToken = "")

    /** An owner that never connects, reading a fixed clock. */
    fun idle(scope: CoroutineScope = CoroutineScope(SupervisorJob())) = PanelAssistantTransportOwner(
        scope = scope,
        auth = { error("an idle owner never resolves a session") },
        connector = { _, _ -> error("an idle owner never connects") },
        monotonicMillis = { 0L },
        observeForHello = { true },
    )

    /** An owner whose every hello Home Assistant refuses with [code], returned once it has been refused. */
    fun refused(code: String, scope: CoroutineScope = CoroutineScope(SupervisorJob())): PanelAssistantTransportOwner {
        val owner = PanelAssistantTransportOwner(
            scope = scope,
            auth = { HaApiSession("https://ha.example", "token", owner = credential) },
            connector = { _, _ -> Refusing(code) },
            observeForHello = { true },
        )
        owner.replaceDemand(
            PanelAssistantTransportDemand(credential, PanelAssistantHelloIdentity(did = "0".repeat(64), appVersion = "1.0.0", appVersionCode = 1)),
        )
        val deadline = System.nanoTime() + 20_000_000_000L
        while (owner.facts().refusal != code) {
            check(System.nanoTime() < deadline) { "the hello was never refused: ${owner.status.describe()}" }
            Thread.sleep(10)
        }
        return owner
    }

    private class Refusing(private val code: String) : PanelAssistantTransportConnection {
        private val replies = Channel<String>(Channel.UNLIMITED)

        override suspend fun send(text: String) {
            replies.send(
                JSONObject().put("id", JSONObject(text).getLong("id")).put("type", "result").put("success", false)
                    .put("error", JSONObject().put("code", code).put("message", "refused")).toString(),
            )
        }

        override suspend fun receive(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { replies.receive() }

        override suspend fun close() {}
    }
}
