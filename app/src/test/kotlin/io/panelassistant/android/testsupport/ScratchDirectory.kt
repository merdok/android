package io.panelassistant.android.testsupport

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

private val scratchRoots = ConcurrentLinkedQueue<File>()

private val scratchSweep: Unit = Runtime.getRuntime().addShutdownHook(Thread {
    scratchRoots.forEach { it.deleteRecursively() }
})

/**
 * A temporary directory that the test JVM removes, with its contents, when it exits.
 * `File.deleteOnExit` only removes empty directories, so tests that fill a directory leak it.
 */
internal fun scratchDirectory(prefix: String): Path {
    scratchSweep
    return Files.createTempDirectory(prefix).also { scratchRoots.add(it.toFile()) }
}
