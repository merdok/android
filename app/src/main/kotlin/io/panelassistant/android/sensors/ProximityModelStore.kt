package io.panelassistant.android.sensors

import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.dashboard.clearProximityLearning
import io.panelassistant.android.dashboard.insertProximityModelIfAbsent
import io.panelassistant.android.dashboard.readProximityModel
import io.panelassistant.android.dashboard.writeProximityBatch

internal interface ProximityModelStore : AutoCloseable {
    fun readProximityModel(fingerprint: String): EntityCatalogStore.ProximityModelRow?
    fun writeProximityBatch(
        model: EntityCatalogStore.ProximityModelRow,
        rollups: List<EntityCatalogStore.ProximityRollupRow>,
        episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
        now: Long,
    )
    fun clearProximityLearning(fingerprint: String)
    fun insertProximityModelIfAbsent(model: EntityCatalogStore.ProximityModelRow): Boolean
}

internal class SqliteProximityModelStore(
    private val store: ProximityModelStore,
) : ProximityModelStore {
    constructor(store: EntityCatalogStore) : this(EntityCatalogProximityModelStore(store))

    override fun readProximityModel(fingerprint: String) = store.readProximityModel(fingerprint)

    override fun writeProximityBatch(
        model: EntityCatalogStore.ProximityModelRow,
        rollups: List<EntityCatalogStore.ProximityRollupRow>,
        episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
        now: Long,
    ) = store.writeProximityBatch(model, rollups, episodes, now)

    override fun clearProximityLearning(fingerprint: String) = store.clearProximityLearning(fingerprint)

    override fun insertProximityModelIfAbsent(model: EntityCatalogStore.ProximityModelRow) =
        store.insertProximityModelIfAbsent(model)

    override fun close() = store.close()
}

private class EntityCatalogProximityModelStore(
    private val store: EntityCatalogStore,
) : ProximityModelStore {
    override fun readProximityModel(fingerprint: String) = store.readProximityModel(fingerprint)

    override fun writeProximityBatch(
        model: EntityCatalogStore.ProximityModelRow,
        rollups: List<EntityCatalogStore.ProximityRollupRow>,
        episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
        now: Long,
    ) = store.writeProximityBatch(model, rollups, episodes, now)

    override fun clearProximityLearning(fingerprint: String) = store.clearProximityLearning(fingerprint)

    override fun insertProximityModelIfAbsent(model: EntityCatalogStore.ProximityModelRow) =
        store.insertProximityModelIfAbsent(model)

    override fun close() = store.close()
}
