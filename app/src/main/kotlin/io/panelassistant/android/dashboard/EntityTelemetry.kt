package io.panelassistant.android.dashboard

internal data class EntityTelemetryTarget(val generation: Long, val instance: String, val path: String)

internal data class EntityTelemetryBatch(
    val target: EntityTelemetryTarget,
    val accessed: Map<String, Long>,
    val missing: Set<String>,
    val metrics: Map<String, Pair<Long, Long>>,
    val now: Long = System.currentTimeMillis(),
) {
    val uniqueIds: Int get() = (accessed.keys + missing + metrics.keys).size
}

internal data class EntityTelemetryAdmission(val backlog: Int, val coalesced: Long, val dropped: Long)

/** Serializes synchronous SQLite telemetry with close; cancellation alone cannot stop a transaction. */
internal class EntityTelemetryStoreGate {
    private val lock = Any()
    fun <T> withStore(block: () -> T): T = synchronized(lock) { block() }
}

/** Fixed-size accumulator used by the renderer bridge. It merges repeated batches without queuing jobs. */
internal class EntityTelemetryAccumulator(private val maxIds: Int) {
    init { require(maxIds > 0) }

    private var target: EntityTelemetryTarget? = null
    private val ids = linkedSetOf<String>()
    private val accessed = linkedMapOf<String, Long>()
    private val missing = linkedSetOf<String>()
    private val metrics = linkedMapOf<String, Pair<Long, Long>>()

    @Synchronized
    fun admit(
        admittedTarget: EntityTelemetryTarget,
        newAccessed: Map<String, Long>,
        newMissing: Set<String>,
        newMetrics: Map<String, Pair<Long, Long>>,
    ): EntityTelemetryAdmission {
        var dropped = 0L
        if (target != null && target != admittedTarget) {
            dropped += ids.size
            clear()
        }
        target = admittedTarget
        val coalesced = if (ids.isNotEmpty() &&
            (newAccessed.isNotEmpty() || newMissing.isNotEmpty() || newMetrics.isNotEmpty())) 1L else 0L

        fun admitId(id: String): Boolean {
            if (id in ids) return true
            if (ids.size >= maxIds) {
                dropped++
                return false
            }
            ids += id
            return true
        }
        newAccessed.forEach { (id, count) ->
            if (admitId(id)) accessed[id] = saturatingAdd(accessed[id] ?: 0L, count.coerceAtLeast(0L))
        }
        newMissing.forEach { id -> if (admitId(id)) missing += id }
        newMetrics.forEach { (id, value) ->
            if (admitId(id)) {
                val previous = metrics[id] ?: (0L to 0L)
                metrics[id] = saturatingAdd(previous.first, value.first.coerceAtLeast(0L)) to
                    saturatingAdd(previous.second, value.second.coerceAtLeast(0L))
            }
        }
        return EntityTelemetryAdmission(ids.size, coalesced, dropped)
    }

    @Synchronized fun drain(): EntityTelemetryBatch? {
        val admittedTarget = target ?: return null
        if (ids.isEmpty()) {
            target = null
            return null
        }
        return EntityTelemetryBatch(
            admittedTarget,
            LinkedHashMap(accessed),
            LinkedHashSet(missing),
            LinkedHashMap(metrics),
        ).also { clear() }
    }

    @Synchronized fun size(): Int = ids.size

    @Synchronized fun discard(): Int = ids.size.also { clear() }

    private fun clear() {
        target = null
        ids.clear()
        accessed.clear()
        missing.clear()
        metrics.clear()
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

/** Serializes the final generation check with evidence reset. A batch that entered first is cleared
 * by reset; a batch that reaches the barrier after reset observes the replacement generation. */
internal class EntityTelemetryWriteBarrier(
    private val currentGeneration: () -> Long,
) {
    private val lock = Any()

    fun writeIfCurrent(admittedGeneration: Long, write: () -> Unit): Boolean = synchronized(lock) {
        if (admittedGeneration != currentGeneration()) return@synchronized false
        write()
        true
    }

    fun invalidateAndWrite(invalidate: () -> Unit, write: () -> Unit) = synchronized(lock) {
        invalidate()
        write()
    }
}

/** Run follow-up work only after releasing the telemetry barrier. Follow-ups may enter the manager
 * monitor; reset takes that monitor before this barrier, so nesting them would invert the lock order. */
internal fun writeEntityTelemetryThen(
    barrier: EntityTelemetryWriteBarrier,
    admittedGeneration: Long,
    write: () -> Unit,
    afterWrite: () -> Unit,
): Boolean {
    val admitted = barrier.writeIfCurrent(admittedGeneration, write)
    if (admitted) afterWrite()
    return admitted
}
