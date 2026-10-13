package io.panelassistant.android.dashboard

import io.panelassistant.android.util.ByteLimitExceeded
import kotlinx.coroutines.runInterruptible
import java.net.SocketTimeoutException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader

/**
 * Rows between progress reports while streaming `/api/states`. Coarse on purpose: the wizard polls every two
 * seconds and eases between the samples it receives, so a finer stride would cost work without the user
 * seeing anything the tween does not already give them.
 */
internal const val HA_STATES_PROGRESS_STRIDE = 25

internal data class HaStatesReadLimits(
    val maxBytes: Long = 64L * 1024 * 1024,
    val maxRows: Int = 100_000,
    val maxEntityIdChars: Int = 255,
    val maxStateChars: Int = 255,
    val maxFriendlyNameChars: Int = 1_024,
    val deadlineMs: Long = 45_000,
) {
    init {
        require(
            maxBytes > 0 && maxRows > 0 && maxEntityIdChars > 0 && maxStateChars > 0 &&
                maxFriendlyNameChars > 0 && deadlineMs > 0,
        )
    }
}

/** Counts bytes consumed by the streaming parser without retaining a second copy. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
}

/** Parse only the state projection needed by the learner while retaining hard transport-independent
 * bounds. The monotonic clock seam keeps elapsed-time enforcement deterministic in unit tests. */
/**
 * @param onProgress called with the running row count as the response streams, every
 *   [HA_STATES_PROGRESS_STRIDE] rows, so the setup wizard can show the count climbing while the user reads
 *   the entity-filter question instead of watching an unexplained pause. Reported counts only ever rise and
 *   never exceed what has actually been parsed, so a partial figure is honest rather than an estimate.
 */
internal fun readHaStates(
    input: InputStream,
    limits: HaStatesReadLimits = HaStatesReadLimits(),
    nanoTime: () -> Long = System::nanoTime,
    onProgress: (Int) -> Unit = {},
): List<EntityCatalogStore.StateRow> {
    val bounded = DeadlineBoundedInputStream(input, limits.maxBytes, limits.deadlineMs, nanoTime)
    return StreamingJsonReader(InputStreamReader(bounded, Charsets.UTF_8)).use { reader ->
        buildList {
            var rows = 0
            reader.beginArray()
            while (reader.hasNext()) {
                check(rows < limits.maxRows) { "states response exceeds ${limits.maxRows} rows" }
                rows++
                var entityId = ""
                var state = ""
                var friendlyName = ""
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "entity_id" -> entityId = reader.readBoundedScalar(limits.maxEntityIdChars, "entity_id")
                    "state" -> state = reader.readBoundedScalar(limits.maxStateChars, "state")
                    "attributes" -> friendlyName = reader.readFriendlyName(limits.maxFriendlyNameChars)
                    else -> reader.skipValue()
                }
                reader.endObject()
                validateHaStateRow(entityId, state, rows, limits, friendlyName)?.let(::add)
                if (rows % HA_STATES_PROGRESS_STRIDE == 0) onProgress(rows)
            }
            reader.endArray()
            onProgress(rows)
            reader.requireEndDocument()
        }
    }
}

internal fun validateHaStateRow(
    entityId: String,
    state: String,
    rowNumber: Int,
    limits: HaStatesReadLimits,
    friendlyName: String = "",
): EntityCatalogStore.StateRow? {
    check(rowNumber <= limits.maxRows) { "states response exceeds ${limits.maxRows} rows" }
    check(entityId.length <= limits.maxEntityIdChars) {
        "states entity_id exceeds ${limits.maxEntityIdChars} characters"
    }
    check(state.length <= limits.maxStateChars) { "states state exceeds ${limits.maxStateChars} characters" }
    check(friendlyName.length <= limits.maxFriendlyNameChars) {
        "states friendly_name exceeds ${limits.maxFriendlyNameChars} characters"
    }
    return entityId.takeIf(String::isNotBlank)?.let { EntityCatalogStore.StateRow(it, state, friendlyName) }
}

/** Selectively project the one state attribute used by static dashboard selectors. All other
 * attributes remain streaming skips and are neither hydrated nor persisted. */
private fun StreamingJsonReader.readFriendlyName(maxChars: Int): String {
    if (peek() != StreamJsonToken.BEGIN_OBJECT) {
        skipValue()
        return ""
    }
    var friendlyName = ""
    beginObject()
    while (hasNext()) when (nextName()) {
        "friendly_name" -> friendlyName = readBoundedScalar(maxChars, "friendly_name")
        else -> skipValue()
    }
    endObject()
    return friendlyName
}

private fun StreamingJsonReader.readBoundedScalar(maxChars: Int, field: String): String {
    val value = when (peek()) {
        StreamJsonToken.NULL -> { nextNull(); "" }
        StreamJsonToken.STRING, StreamJsonToken.NUMBER -> nextString(maxChars, field)
        StreamJsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }
    check(value.length <= maxChars) { "states $field exceeds $maxChars characters" }
    return value
}

internal enum class StreamJsonToken { BEGIN_OBJECT, BEGIN_ARRAY, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/** Small dependency-free streaming JSON reader for the HA state projection. Android's JsonReader is
 * unavailable as real code in local JVM tests, while a tree parser would reintroduce the payload-copy
 * regression this path exists to avoid. */
internal class StreamingJsonReader(private val input: Reader) : AutoCloseable {
    private data class Scope(val closing: Char, var first: Boolean = true)
    private val scopes = ArrayDeque<Scope>()
    private var lookahead = UNSET

    fun beginArray() { expect('['); pushScope(']') }
    fun endArray() { expect(']'); popScope(']') }
    fun beginObject() { expect('{'); pushScope('}') }
    fun endObject() { expect('}'); popScope('}') }

    fun hasNext(): Boolean {
        val scope = scopes.lastOrNull() ?: error("JSON scope is empty")
        skipWhitespace()
        if (peekChar() == scope.closing.code) return false
        if (scope.first) {
            scope.first = false
        } else {
            expect(',')
            skipWhitespace()
            check(peekChar() != scope.closing.code) { "trailing comma in JSON" }
        }
        return true
    }

    fun nextName(): String {
        val name = readString(MAX_NAME_CHARS, "field name")
        skipWhitespace(); expect(':')
        return name
    }

    fun peek(): StreamJsonToken {
        skipWhitespace()
        return when (val char = peekChar()) {
            '{'.code -> StreamJsonToken.BEGIN_OBJECT
            '['.code -> StreamJsonToken.BEGIN_ARRAY
            '"'.code -> StreamJsonToken.STRING
            't'.code, 'f'.code -> StreamJsonToken.BOOLEAN
            'n'.code -> StreamJsonToken.NULL
            -1 -> StreamJsonToken.END_DOCUMENT
            else -> if (char == '-'.code || char in '0'.code..'9'.code) StreamJsonToken.NUMBER
                else error("invalid JSON value")
        }
    }

    fun nextNull() { expectLiteral("null") }
    fun nextBoolean(): Boolean = when (peekChar()) {
        't'.code -> { expectLiteral("true"); true }
        'f'.code -> { expectLiteral("false"); false }
        else -> error("expected JSON boolean")
    }

    fun nextString(maxChars: Int, field: String): String = when (peek()) {
        StreamJsonToken.STRING -> readString(maxChars, field)
        StreamJsonToken.NUMBER -> readNumber(maxChars, field)
        else -> error("expected JSON scalar")
    }

    fun skipValue(depth: Int = 0) {
        check(depth < MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH" }
        when (peek()) {
            StreamJsonToken.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) { nextName(); skipValue(depth + 1) }
                endObject()
            }
            StreamJsonToken.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue(depth + 1)
                endArray()
            }
            StreamJsonToken.STRING -> { readString(null, "skipped value") }
            StreamJsonToken.NUMBER -> { readNumber(MAX_SKIPPED_NUMBER_CHARS, "skipped number") }
            StreamJsonToken.BOOLEAN -> { nextBoolean() }
            StreamJsonToken.NULL -> nextNull()
            StreamJsonToken.END_DOCUMENT -> error("unexpected end of JSON")
        }
    }

    fun requireEndDocument() {
        check(scopes.isEmpty()) { "JSON scope is still open" }
        skipWhitespace()
        check(peekChar() == -1) { "trailing data after JSON document" }
    }

    override fun close() = input.close()

    private fun readString(maxChars: Int?, field: String): String {
        skipWhitespace(); expect('"')
        val value = if (maxChars == null) null else StringBuilder(minOf(maxChars, 64))
        var count = 0
        while (true) {
            val char = takeChar()
            check(char >= 0) { "unterminated JSON string" }
            if (char == '"'.code) break
            val decoded = if (char == '\\'.code) readEscape() else {
                check(char >= 0x20) { "control character in JSON string" }
                char.toChar()
            }
            count++
            if (maxChars != null) check(count <= maxChars) { "states $field exceeds $maxChars characters" }
            value?.append(decoded)
        }
        return value?.toString().orEmpty()
    }

    private fun readEscape(): Char = when (val escaped = takeChar().toChar()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
        'u' -> {
            var value = 0
            repeat(4) {
                val digit = takeChar()
                val hex = Character.digit(digit.toChar(), 16)
                check(hex >= 0) { "invalid JSON unicode escape" }
                value = value * 16 + hex
            }
            value.toChar()
        }
        else -> error("invalid JSON escape")
    }

    private fun readNumber(maxChars: Int, field: String): String {
        skipWhitespace()
        val value = StringBuilder()
        while (true) {
            val char = peekChar()
            if (char < 0 || char.toChar().isWhitespace() || char == ','.code || char == ']'.code || char == '}'.code) break
            value.append(takeChar().toChar())
            check(value.length <= maxChars) { "states $field exceeds $maxChars characters" }
        }
        return value.toString().also { check(JSON_NUMBER.matches(it)) { "invalid JSON number" } }
    }

    private fun expectLiteral(literal: String) = literal.forEach { expected ->
        check(takeChar() == expected.code) { "invalid JSON literal" }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        check(takeChar() == expected.code) { "expected '$expected' in JSON" }
    }

    private fun pushScope(closing: Char) {
        check(scopes.size < MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH" }
        scopes.addLast(Scope(closing))
    }

    private fun popScope(closing: Char) {
        check(scopes.removeLastOrNull()?.closing == closing) { "mismatched JSON scope" }
    }

    private fun skipWhitespace() { while (peekChar() >= 0 && peekChar().toChar().isWhitespace()) takeChar() }
    private fun peekChar(): Int {
        if (lookahead == UNSET) lookahead = input.read()
        return lookahead
    }
    private fun takeChar(): Int = peekChar().also { lookahead = UNSET }

    private companion object {
        const val UNSET = -2
        const val MAX_DEPTH = 64
        const val MAX_NAME_CHARS = 1_024
        const val MAX_SKIPPED_NUMBER_CHARS = 1_024
        val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

/** Bounds bytes before the streaming reader can allocate field values. Cancellation interrupts the blocking read through
 * runInterruptible; this stream also notices interruption and elapsed time between transport reads. */
internal class DeadlineBoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    deadlineMs: Long,
    private val nanoTime: () -> Long,
) : FilterInputStream(input) {
    private val startedAt = nanoTime()
    private val deadlineNanos = deadlineMs * 1_000_000L
    private var total = 0L

    override fun read(): Int {
        checkAbort()
        val value = super.read()
        checkAbort()
        if (value < 0) return value
        if (++total > maxBytes) throw ByteLimitExceeded(maxBytes)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkAbort()
        val wanted = minOf(length.toLong(), maxBytes - total + 1L).toInt()
        val count = super.read(buffer, offset, wanted)
        checkAbort()
        if (count < 0) return count
        total += count
        if (total > maxBytes) throw ByteLimitExceeded(maxBytes)
        return count
    }

    private fun checkAbort() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("states response read cancelled")
        if (nanoTime() - startedAt >= deadlineNanos) throw SocketTimeoutException("states response read deadline exceeded")
    }
}
