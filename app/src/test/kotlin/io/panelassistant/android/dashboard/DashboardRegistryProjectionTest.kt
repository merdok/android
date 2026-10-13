package io.panelassistant.android.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRegistryProjectionTest {
    @Test fun registryProjectionCarriesEffectiveAreaFloorLabelNamesAndAreaOverrides() {
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[
              {"ei":"light.lamp","di":"device_1","lb":["urgent"]},
              {"ei":"sensor.remote_temperature"}
            ]}}"""),
            JSONObject("""{"result":[{
              "area_id":"living_room","name":"Living Room","floor_id":"ground_floor",
              "temperature_entity_id":"sensor.remote_temperature"
            }]}"""),
            JSONObject("""{"result":[{
              "id":"device_1","area_id":"living_room","labels":["portable"]
            }]}"""),
            JSONObject("""{"result":[{"floor_id":"ground_floor","name":"Ground Floor"}]}"""),
            JSONObject("""{"result":[
              {"label_id":"urgent","name":"Needs Attention"},
              {"label_id":"portable","name":"Portable"}
            ]}"""),
        )

        assertTrue(projection.complete)
        val metadata = JSONObject(projection.metadata.getValue("light.lamp"))
        assertEquals("living_room", metadata.getString("ai"))
        assertEquals("Living Room", metadata.getString("an"))
        assertEquals("ground_floor", metadata.getString("fi"))
        assertEquals("Ground Floor", metadata.getString("fn"))
        assertEquals(setOf("portable", "urgent"), (0 until metadata.getJSONArray("lb").length()).map {
            metadata.getJSONArray("lb").getString(it)
        }.toSet())
        assertEquals(setOf("Needs Attention", "Portable"), (0 until metadata.getJSONArray("ln").length()).map {
            metadata.getJSONArray("ln").getString(it)
        }.toSet())
        assertEquals(
            setOf("sensor.remote_temperature"),
            projection.areaRegistryEntities.getValue("living_room"),
        )
    }

    @Test fun registryProjectionFailsClosedForMissingOrMalformedEntityRows() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")

        val missing = projectDashboardRegistries(
            JSONObject("""{"result":{}}"""), emptyResponse(), emptyResponse(), emptyResponse(), emptyResponse(),
        )
        assertFalse(missing.complete)

        val malformed = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"not-an-entity"},null]}}"""),
            emptyResponse(), emptyResponse(), emptyResponse(), emptyResponse(),
        )
        assertFalse(malformed.complete)
        assertTrue(malformed.metadata.isEmpty())
    }

    @Test fun registryProjectionAcceptsACompleteEmptyRegistrySnapshot() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[]}}"""),
            emptyResponse(), emptyResponse(), emptyResponse(), emptyResponse(),
        )

        assertTrue(projection.complete)
        assertTrue(projection.metadata.isEmpty())
        assertTrue(projection.areaRegistryEntities.isEmpty())
    }

    @Test fun registryProjectionSkipsAllRegistryPayloadsWhenDashboardNeedsNone() {
        val projection = projectDashboardRegistries(
            null, null, null, null, null,
            DashboardConfigurationLint.RegistryRequirements(),
        )

        assertTrue(projection.complete)
        assertTrue(projection.metadata.isEmpty())
        assertTrue(projection.areaRegistryEntities.isEmpty())
    }

    @Test fun registryProjectionForMapNeedsOnlyEntityRegistry() {
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"zone.home"}]}}"""),
            null, null, null, null,
            DashboardConfigurationLint.RegistryRequirements(entities = true),
        )

        assertTrue(projection.complete)
        assertEquals(setOf("zone.home"), projection.metadata.keys)
        assertTrue(projection.areaRegistryEntities.isEmpty())
    }

    @Test fun areaProjectionDoesNotRequireUnusedFloorOrLabelRegistries() {
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"light.lamp","di":"device_1"}]}}"""),
            JSONObject("""{"result":[{
              "area_id":"study","name":"Study","floor_id":"upper",
              "temperature_entity_id":"sensor.remote_temperature"
            }]}"""),
            JSONObject("""{"result":[{"id":"device_1","area_id":"study","labels":["unused"]}]}"""),
            null,
            null,
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
        )

        assertTrue(projection.complete)
        val metadata = JSONObject(projection.metadata.getValue("light.lamp"))
        assertEquals("study", metadata.getString("ai"))
        assertEquals("Study", metadata.getString("an"))
        assertFalse(metadata.has("fi"))
        assertFalse(metadata.has("lb"))
        assertEquals(setOf("sensor.remote_temperature"), projection.areaRegistryEntities.getValue("study"))
    }

    @Test fun registryProjectionFailsClosedOnlyForRequiredCapabilities() {
        val missingEntity = projectDashboardRegistries(
            null, null, null, null, null,
            DashboardConfigurationLint.RegistryRequirements(entities = true),
        )
        assertFalse(missingEntity.complete)

        val malformedUnused = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"zone.home"}]}}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            DashboardConfigurationLint.RegistryRequirements(entities = true),
        )
        assertTrue(malformedUnused.complete)
    }

    @Test fun registryProjectionRejectsMalformedRequiredDisplayNames() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"light.lamp","ai":"living_room"}]}}"""),
            JSONObject("""{"result":[{"area_id":"living_room"}]}"""),
            emptyResponse(),
            null,
            null,
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
        )

        assertFalse(projection.complete)
    }

    @Test fun registryProjectionRejectsMalformedEntityAndDeviceLabelFields() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")
        fun project(entityLabels: String, deviceLabels: String) = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{
              "ei":"light.lamp","di":"device_1","lb":$entityLabels
            }]}}"""),
            null,
            JSONObject("""{"result":[{"id":"device_1","labels":$deviceLabels}]}"""),
            null,
            emptyResponse(),
            DashboardConfigurationLint.RegistryRequirements(entities = true, devices = true, labels = true),
        )

        assertFalse(project("{}", "[]").complete)
        assertFalse(project("[]", "7").complete)
        assertFalse(project("[\"valid\",7]", "[]").complete)
    }
}
