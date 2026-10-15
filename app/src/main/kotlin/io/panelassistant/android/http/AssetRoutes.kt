package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Bundled front-end assets, mounted beneath the shared production root guards. */
internal fun Route.assetRoutes(asset: (String) -> String) {
    // Static front-end assets (externalised from the Kotlin string so CI can lint them).
    get("/info.js") {
        call.response.headers.append("Cache-Control", "no-cache")  // assets iterate; always serve fresh
        call.respondText(asset("info.js"), ContentType.Application.JavaScript)
    }
    get("/info.css") {
        call.response.headers.append("Cache-Control", "no-cache")
        call.respondText(asset("info.css"), ContentType.Text.CSS)
    }
    // The mark from the brand asset set; browsers ask for /favicon.svg too, so both names serve it.
    for (path in BUNDLE_ROOT_FILES.keys.filter { it.endsWith(".svg") }) get("/$path") {
        call.respondText(asset(BUNDLE_ROOT_FILES.getValue(path)), ContentType.Image.SVG)
    }
    // Generic bundled-asset server for the redesigned UI (page scripts + vendored libs).
    get("/assets/{f...}") {
        val rel = call.parameters.getAll("f")?.joinToString("/").orEmpty()
        val body = if (rel.isEmpty() || rel.contains("..")) null else runCatching { asset(rel) }.getOrNull()
        if (body == null) {
            call.respondText("not found\n", status = HttpStatusCode.NotFound)
        } else {
            val ct = when {
                rel.endsWith(".js") -> ContentType.Application.JavaScript
                rel.endsWith(".css") -> ContentType.Text.CSS
                rel.endsWith(".svg") -> ContentType.Image.SVG
                rel.endsWith(".json") -> ContentType.Application.Json
                else -> ContentType.Text.Plain
            }
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondText(body, ct)
        }
    }
}

/**
 * The panel's bundle (protocol section 20.5): every file under the APK's `assets/`, by its path there, and its
 * bytes. The root names the routes above serve ([BUNDLE_ROOT_FILES]) are assets too.
 */
internal interface UiBundleAssets {
    fun names(): List<String>
    fun bytes(name: String): ByteArray

    companion object {
        /**
         * The `assets/` entries of the installed APK at [apk], read from the APK itself: the asset manager's root
         * listing also names the framework's own asset directories, which the panel never serves.
         */
        fun ofApk(apk: () -> String): UiBundleAssets = object : UiBundleAssets {
            override fun names(): List<String> = java.util.zip.ZipFile(apk()).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(PREFIX) }.map { it.name.removePrefix(PREFIX) }.toList()
            }

            override fun bytes(name: String): ByteArray = java.util.zip.ZipFile(apk()).use { zip ->
                val entry = zip.getEntry(PREFIX + name) ?: throw java.io.FileNotFoundException(name)
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }

        private const val PREFIX = "assets/"
    }
}

/** The root paths the routes above serve, by the asset each serves. */
internal val BUNDLE_ROOT_FILES: Map<String, String> =
    linkedMapOf("info.js" to "info.js", "info.css" to "info.css", "icon.svg" to "icon.svg", "favicon.svg" to "icon.svg")

/**
 * Write the bundle as one zip: exactly the files the stub's bundle routes serve, at the paths the page base sees
 * them (`assets/<path>` and the root names), so Panel Assistant can serve the sidebar frame without the panel.
 */
internal fun writeUiBundle(assets: UiBundleAssets, out: java.io.OutputStream) {
    java.util.zip.ZipOutputStream(out).use { zip ->
        fun entry(path: String, bytes: ByteArray) {
            zip.putNextEntry(java.util.zip.ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
        BUNDLE_ROOT_FILES.forEach { (path, name) -> entry(path, assets.bytes(name)) }
        assets.names().sorted().forEach { name -> entry("assets/$name", assets.bytes(name)) }
    }
}
