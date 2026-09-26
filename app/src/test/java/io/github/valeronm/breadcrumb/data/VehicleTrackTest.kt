package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A vehicle is applied where a track finishes: a trip made with one of its links connected for most
 * of it takes the vehicle's type and id, and one that only brushed a link keeps what it was.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleTrackTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao
    private val vehicles = VehicleRepository(ApplicationProvider.getApplicationContext(), test.db)

    @After fun tearDown() = test.close()

    private suspend fun carWithBluetooth(): Pair<Long, Long> {
        val car = vehicles.create("Car", ActivityType.DRIVING)
        assertTrue(vehicles.addLink(car, VehicleLinkKind.BLUETOOTH, "00:11:22:33:44:55", "Car kit"))
        val link = vehicles.linksByKey().getValue(VehicleLinkKind.BLUETOOTH to "00:11:22:33:44:55")
        return car to link.id
    }

    @Test fun `a trip with the car connected throughout finishes as the car`() = runTest {
        val (car, link) = carWithBluetooth()
        vehicles.logConnection(link, TEST_START - 60_000L, connected = true)

        val id = test.walk(TEST_START, 0, 5)

        val track = dao.track(id)!!
        assertEquals(ActivityType.DRIVING.name, track.activityType)
        assertEquals(car, track.vehicleId)
        assertEquals("Car", vehicles.observeVehicleOf(id).first()?.name)
    }

    @Test fun `a walk that only brushed the car keeps its label and no vehicle`() = runTest {
        val (_, link) = carWithBluetooth()
        vehicles.logConnection(link, TEST_START - 60_000L, connected = true)
        vehicles.logConnection(link, TEST_START + 5_000L, connected = false)

        val id = test.walk(TEST_START, 0, 5)

        val track = dao.track(id)!!
        assertEquals(ActivityType.WALKING.name, track.activityType)
        assertNull(track.vehicleId)
    }

    @Test fun `with no vehicles set up a finish is exactly what it was`() = runTest {
        val id = test.walk(TEST_START, 0, 5)
        assertEquals(ActivityType.WALKING.name, dao.track(id)!!.activityType)
        assertNull(dao.track(id)!!.vehicleId)
    }

    @Test fun `the log keeps changes, not repeats`() = runTest {
        val (car, link) = carWithBluetooth()
        vehicles.logConnection(link, 1_000L, connected = true)
        vehicles.logConnection(link, 2_000L, connected = true)
        vehicles.logConnection(link, 3_000L, connected = false)
        vehicles.logConnection(link, 4_000L, connected = false)

        val events = test.db.vehicleDao().connectionsBetween(0L, 10_000L)
        assertEquals(listOf(1_000L to true, 3_000L to false), events.map { it.atMs to it.connected })
        assertTrue(events.all { it.vehicleId == car })
    }

    @Test fun `one device stands for one vehicle`() = runTest {
        carWithBluetooth()
        val van = vehicles.create("Van", ActivityType.DRIVING)
        assertFalse(vehicles.addLink(van, VehicleLinkKind.BLUETOOTH, "00:11:22:33:44:55", "Car kit"))
        // The same name as a Wi-Fi network is another link altogether.
        assertTrue(vehicles.addLink(van, VehicleLinkKind.WIFI, "00:11:22:33:44:55", "odd name"))
    }

    @Test fun `deleting a vehicle keeps its trips' type and drops only the reference`() = runTest {
        val (car, link) = carWithBluetooth()
        vehicles.logConnection(link, TEST_START - 60_000L, connected = true)
        val id = test.walk(TEST_START, 0, 5)

        vehicles.delete(car)

        val track = dao.track(id)!!
        assertEquals(ActivityType.DRIVING.name, track.activityType)
        assertNull(track.vehicleId)
        assertTrue(vehicles.linksByKey().isEmpty())
        assertTrue(test.db.vehicleDao().connectionsBetween(0L, Long.MAX_VALUE).isEmpty())
    }

    @Test fun `retyping away from the vehicle's type takes the trip out of it`() = runTest {
        val (car, link) = carWithBluetooth()
        vehicles.logConnection(link, TEST_START - 60_000L, connected = true)
        val id = test.walk(TEST_START, 0, 5)

        vehicles.retyped(id, ActivityType.DRIVING)
        assertEquals(car, dao.track(id)!!.vehicleId)

        repository.setActivityType(id, ActivityType.TAXI)
        vehicles.retyped(id, ActivityType.TAXI)
        assertNull(dao.track(id)!!.vehicleId)
    }

    @Test fun `a split hands the vehicle to both halves`() = runTest {
        val (car, link) = carWithBluetooth()
        vehicles.logConnection(link, TEST_START - 60_000L, connected = true)
        val id = test.walk(TEST_START, 0, 9)

        val split = checkNotNull(repository.splitTrack(id, TEST_START + 50_000L))

        assertEquals(car, dao.track(id)!!.vehicleId)
        assertEquals(car, dao.track(split.secondId)!!.vehicleId)
    }
}
