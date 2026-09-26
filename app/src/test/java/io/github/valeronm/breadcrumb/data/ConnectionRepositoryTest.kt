package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One device or network stands for one thing across both link tables: the editors refuse one that
 * is taken, and Settings → Connections moves it, taking it from where it was.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionRepositoryTest {

    private val test = TestDb()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connections = ConnectionRepository(context, test.db)
    private val vehicles = VehicleRepository(context, test.db)

    @After fun tearDown() = test.close()

    private suspend fun place(label: String): Long =
        test.db.placeDao().insert(Place(label = label, lat = 1.0, lon = -2.0, createdAt = 0L, radiusM = 75.0))

    private suspend fun owners() = connections.observeConnections().first().associate { it.key to it.owner }

    @Test fun `a network a place has is refused to a vehicle, and the other way round`() = runTest {
        val home = place("Home")
        val car = vehicles.create("Car", ActivityType.DRIVING)

        assertTrue(connections.add(VehicleLinkKind.WIFI, "HomeNet", "HomeNet", LinkOwner.OfPlace(home)))
        assertFalse(vehicles.addLink(car, VehicleLinkKind.WIFI, "HomeNet", "HomeNet"))
        assertFalse(connections.add(VehicleLinkKind.WIFI, "HomeNet", "HomeNet", LinkOwner.OfVehicle(car)))

        assertTrue(vehicles.addLink(car, VehicleLinkKind.BLUETOOTH, "00:11:22:33:44:55", "Car kit"))
        assertFalse(
            connections.add(VehicleLinkKind.BLUETOOTH, "00:11:22:33:44:55", "Car kit", LinkOwner.OfPlace(home)),
        )

        assertEquals(
            mapOf("HomeNet" to LinkOwner.OfPlace(home), "00:11:22:33:44:55" to LinkOwner.OfVehicle(car)),
            owners(),
        )
    }

    @Test fun `adding what the owner already has changes nothing`() = runTest {
        val home = place("Home")
        assertTrue(connections.add(VehicleLinkKind.WIFI, "HomeNet", "HomeNet", LinkOwner.OfPlace(home)))
        assertTrue(connections.add(VehicleLinkKind.WIFI, "HomeNet", "HomeNet", LinkOwner.OfPlace(home)))
        assertEquals(1, connections.observeConnections().first().size)
    }

    @Test fun `assigning moves a network between a place and a vehicle`() = runTest {
        val home = place("Home")
        val car = vehicles.create("Car", ActivityType.DRIVING)
        connections.add(VehicleLinkKind.WIFI, "Hotspot", "Hotspot", LinkOwner.OfPlace(home))

        connections.assign(VehicleLinkKind.WIFI, "Hotspot", "Hotspot", LinkOwner.OfVehicle(car))
        assertEquals(mapOf("Hotspot" to LinkOwner.OfVehicle(car)), owners())
        assertTrue(connections.placeLinksByKey().isEmpty())
        assertEquals(setOf(VehicleLinkKind.WIFI to "Hotspot"), vehicles.linksByKey().keys)

        connections.assign(VehicleLinkKind.WIFI, "Hotspot", "Hotspot", LinkOwner.OfPlace(home))
        assertEquals(mapOf("Hotspot" to LinkOwner.OfPlace(home)), owners())
        assertTrue(vehicles.linksByKey().isEmpty())
    }

    @Test fun `a place's links go with it`() = runTest {
        val home = place("Home")
        connections.add(VehicleLinkKind.WIFI, "HomeNet", "HomeNet", LinkOwner.OfPlace(home))
        assertEquals("Home", connections.placeLabel(home))

        test.db.placeDao().delete(home)

        assertTrue(connections.observeConnections().first().isEmpty())
    }

    @Test fun `removing unties it from whatever held it`() = runTest {
        val home = place("Home")
        connections.add(VehicleLinkKind.WIFI, "HomeNet", "HomeNet", LinkOwner.OfPlace(home))
        connections.remove(VehicleLinkKind.WIFI, "HomeNet")
        assertTrue(connections.observeConnections().first().isEmpty())
    }

    /** A track that ended at [placeId]'s cluster at [atMs], as the derivation would store it. */
    private fun endedAt(trackId: Long, placeId: Long?, atMs: Long) {
        val sql = test.db.openHelper.writableDatabase
        sql.execSQL(
            "INSERT INTO derived_clusters (id, placeId, anchorLat, anchorLon, radiusM, sumLat, sumLon, memberCount) " +
                "VALUES ($trackId, ${placeId ?: "NULL"}, 1.0, -2.0, 75.0, 1.0, -2.0, 1)",
        )
        sql.execSQL(
            "INSERT INTO cluster_members (clusterId, trackId, isStart, lat, lon, atMs) " +
                "VALUES ($trackId, $trackId, 0, 1.0, -2.0, $atMs)",
        )
    }

    @Test fun `a network seen while standing is remembered with the place the last trip ended at`() = runTest {
        val home = place("Home")
        val work = place("Work")
        endedAt(1, work, 1_000L)
        endedAt(2, home, 2_000L)

        connections.noteSeen("HomeNet", 3_000L, moving = false)

        val seen = connections.observeSeenNetworks().first().single()
        assertEquals("HomeNet", seen.ssid)
        assertEquals(3_000L, seen.lastSeenAt)
        assertEquals(home, seen.placeId)
    }

    @Test fun `connecting on the move keeps the place it was seen at before`() = runTest {
        val home = place("Home")
        endedAt(1, home, 1_000L)
        connections.noteSeen("HomeNet", 2_000L, moving = false)

        connections.noteSeen("HomeNet", 9_000L, moving = true)

        val seen = connections.observeSeenNetworks().first().single()
        assertEquals(9_000L, seen.lastSeenAt)
        assertEquals(home, seen.placeId)
    }

    @Test fun `an unnamed stop suggests no place`() = runTest {
        endedAt(1, null, 1_000L)
        connections.noteSeen("CafeNet", 2_000L, moving = false)
        assertEquals(null, connections.observeSeenNetworks().first().single().placeId)
    }

    @Test fun `seen networks age out and can be forgotten`() = runTest {
        connections.noteSeen("Old", 1_000L, moving = true)
        connections.noteSeen("Kept", 5_000L, moving = true)
        connections.noteSeen("Unwanted", 6_000L, moving = true)

        connections.purgeSeen(2_000L)
        connections.forgetSeen("Unwanted")

        assertEquals(listOf("Kept"), connections.observeSeenNetworks().first().map { it.ssid })
    }
}
