package io.panelassistant.android.http

import io.panelassistant.android.camera.CameraFault
import io.panelassistant.android.camera.CameraIndication
import io.panelassistant.android.camera.CameraPresentation
import io.panelassistant.android.camera.CameraResolution
import io.panelassistant.android.camera.CameraState
import io.panelassistant.android.camera.SnapshotExposureOutcome
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binds the camera projection (`CameraPresentation`, proven behaviourally in `CameraPresentationTest`)
 * to the surfaces that must carry it: `/api/v1/status`, `/api/v1/diag` and the OpenAPI document. Modelled
 * on `RendererHealthSurfaceContractTest` — the same class of silent drift applies here: a status field
 * that quietly stopped being emitted, or an OpenAPI schema that fell out of sync with what the panel
 * actually sends, reads as a healthy fleet from the outside.
 */
class CameraSurfaceContractTest {
    // Source-text reason: the shipped openapi.json is the public API contract, parsed as data.
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }

    @Test fun openApiListsCameraAsAlwaysPresentOnStatusAndMentionsItOnDiag() {
        val status = openApi.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
        val schema = status.getJSONObject("responses").getJSONObject("200")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        assertTrue(schema.getJSONArray("required").toString().contains("camera"))
        assertTrue(schema.getJSONObject("properties").has("camera"))
        assertTrue(openApi.getJSONObject("paths").getJSONObject("/api/v1/diag").toString().contains("camera"))
    }

    @Test fun everyFieldTheProjectionEmitsIsDocumentedAndRequired() {
        // Derived from what `statusJson()` actually produces rather than transcribed beside it, exactly
        // as the renderer's equivalent test does — the schema and the emitted keys are compared as sets
        // in both directions so neither can drift from the other unnoticed.
        val emitted = JSONObject(CameraPresentation.absent().statusJson()).keys().asSequence().toSet()
        val schema = openApi.getJSONObject("components").getJSONObject("schemas").getJSONObject("CameraHealth")
        val documented = schema.getJSONObject("properties").keys().asSequence().toSet()
        val required = schema.getJSONArray("required")
            .let { array -> (0 until array.length()).map { array.getString(it) } }.toSet()

        assertEquals("OpenAPI documents fields the panel does not emit", emitted, documented)
        assertEquals("the object is always fully populated, so every field is required", emitted, required)
    }

    @Test fun theDocumentedEnumsMatchTheKotlinWireValuesExactly() {
        val properties = openApi.getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("CameraHealth").getJSONObject("properties")
        fun enumOf(field: String): Set<String> =
            properties.getJSONObject(field).getJSONArray("enum")
                .let { array -> (0 until array.length()).map { array.getString(it) } }.toSet()

        assertEquals(CameraState.entries.map { it.wire }.toSet(), enumOf("state"))
        assertEquals(CameraFault.entries.map { it.wire }.toSet(), enumOf("fault"))
        assertEquals(CameraIndication.entries.map { it.wire }.toSet(), enumOf("indication"))
        assertEquals(SnapshotExposureOutcome.entries.map { it.wire }.toSet(), enumOf("snapshot_exposure"))
    }

    @Test fun theConfigSchemaDescribesTheFourCameraSettings() {
        val properties = openApi.getJSONObject("paths").getJSONObject("/api/v1/config").getJSONObject("post")
            .getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/x-www-form-urlencoded").getJSONObject("schema")
            .getJSONObject("properties")

        val enabled = properties.getJSONObject("camera_enabled")
        assertEquals("boolean", enabled.getString("type"))
        assertFalse(enabled.getBoolean("default"))

        val resolution = properties.getJSONObject("camera_resolution")
        assertEquals("720p", resolution.getString("default"))
        assertEquals(
            CameraResolution.entries.map { it.wire },
            resolution.getJSONArray("enum").let { array -> (0 until array.length()).map { array.getString(it) } },
        )

        val fps = properties.getJSONObject("camera_fps")
        assertEquals(1, fps.getInt("minimum"))
        assertEquals(30, fps.getInt("maximum"))
        assertEquals(15, fps.getInt("default"))

        val kbps = properties.getJSONObject("camera_kbps")
        assertEquals(250, kbps.getInt("minimum"))
        assertEquals(8000, kbps.getInt("maximum"))
        assertEquals(2000, kbps.getInt("default"))
    }
}
