package io.panelassistant.android.http

import io.panelassistant.android.camera.CameraPresentation
import io.panelassistant.android.camera.CameraResolution
import io.panelassistant.android.camera.CameraSurface
import io.panelassistant.android.camera.SnapshotResult
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRoutesTest {
    @Test fun `status stays passive and the retired JPEG route cannot capture`() {
        var snapshots = 0
        val camera = object : CameraSurface {
            override fun presentation() = CameraPresentation.absent()
            override fun snapshot(requested: CameraResolution?): SnapshotResult {
                snapshots++
                return SnapshotResult.Jpeg(byteArrayOf(1, 2, 3))
            }
        }
        PaneldServerHttpFixture(camera = camera).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val status = client.get("/api/v1/camera/status") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.OK, status.status)
                assertEquals(camera.presentation().statusJson(), status.bodyAsText())
                assertEquals("no-store", status.headers[HttpHeaders.CacheControl])
                assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/camera/snapshot.jpg").status)
                assertEquals(0, snapshots)
            }
        }
    }
}
