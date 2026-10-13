package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageBlobs
import io.panelassistant.android.ManageDoor
import io.panelassistant.android.ManageOperations
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageSink
import io.panelassistant.android.ManageTable
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A door onto [table]'s named operations, as a dispatcher serves them; channel commands are absent here. */
internal fun tableDoor(table: ManageTable): ManageDoor {
    val operations = ManageOperations(table, { LocalApprovalBroker.instance })
    return object : ManageDoor {
        override fun manage(
            commandId: String, op: String, args: JSONObject, origin: CommandOrigin,
            blobs: ManageBlobs?, onPending: () -> Unit, done: (ManageReply) -> Unit,
        ) = operations.manage(commandId, op, args, origin, blobs, onPending, done)
        override fun submit(command: PanelAssistantCommand, origin: CommandOrigin, done: (PanelAssistantCommandResult) -> Unit) =
            done(PanelAssistantCommandResult.Failed("failed"))
        override fun subscribe(family: String, origin: CommandOrigin, args: JSONObject, sink: ManageSink) =
            operations.subscribe(family, origin, args, sink)
        override fun closeOrigin(origin: CommandOrigin) = operations.closeOrigin(origin)
    }
}

/** Mount the stub's door onto [table], beside the routes that registered into it. */
internal fun Route.socketDoorOnto(table: ManageTable) {
    val door = tableDoor(table)
    stubRoutes({ door }, StubBlobs(), health = { "health\n" }, diag = { "diag\n" })
}

/** Send one request object over a fresh `/ui` socket, as the page transport does, and return its final answer. */
internal suspend fun ApplicationTestBuilder.socketRequest(op: String, args: JSONObject = JSONObject()): JSONObject {
    var answer: JSONObject? = null
    // A fresh id: the door replays a repeated command id from the same peer (protocol section 5).
    val id = "q" + java.util.UUID.randomUUID()
    createClient { install(WebSockets) }.webSocket("/ui") {
        send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args).toString()))
        answer = withTimeout(10_000) {
            var frame: JSONObject
            do { frame = JSONObject((incoming.receive() as Frame.Text).readText()) } while (frame.optString("command_id") != id || frame.optBoolean("interim"))
            frame
        }
    }
    return requireNotNull(answer)
}
