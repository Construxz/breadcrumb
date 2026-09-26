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
}
