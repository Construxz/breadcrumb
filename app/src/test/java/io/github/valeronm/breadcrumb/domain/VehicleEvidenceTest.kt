package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.VehicleEvidence.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleEvidenceTest {

    private val min = 60_000L
    private val car = 1L
    private val van = 2L

    private fun on(vehicle: Long, link: Long, at: Long) = Event(vehicle, link, at * min, connected = true)
    private fun off(vehicle: Long, link: Long, at: Long) = Event(vehicle, link, at * min, connected = false)

    private fun vehicleFor(events: List<Event>, from: Long, to: Long) =
        VehicleEvidence.vehicleFor(events, from * min, to * min)

    @Test fun `a drive with the car connected throughout is the car's`() {
        val events = listOf(on(car, 10, 0), off(car, 10, 40))
        assertEquals(car, vehicleFor(events, 2, 38))
    }

    @Test fun `a walk away from the car, still in range for its first steps, is nobody's`() {
        val events = listOf(on(car, 10, 0), off(car, 10, 32))
        assertNull(vehicleFor(events, 30, 45))
    }

    @Test fun `half the track is enough, less is not`() {
        val events = listOf(on(car, 10, 0), off(car, 10, 10))
        assertEquals(car, vehicleFor(events, 5, 15))
        assertNull(vehicleFor(events, 6, 20))
    }

    @Test fun `a link still connected at the end counts to the end`() {
        assertEquals(car, vehicleFor(listOf(on(car, 10, 0)), 5, 60))
    }

    @Test fun `a disconnect with no connect before it in the window was connected since before`() {
        assertEquals(car, vehicleFor(listOf(off(car, 10, 30)), 5, 35))
    }

    @Test fun `bluetooth and hotspot of one car together are not counted twice`() {
        // Each link alone covers 40% of the track; together they overlap into 60%.
        val events = listOf(on(car, 10, 0), off(car, 10, 4), on(car, 11, 2), off(car, 11, 6))
        assertEquals(car, vehicleFor(events, 0, 10))
        // Overlapping fully, they still cover only 40%.
        val same = listOf(on(car, 10, 0), off(car, 10, 4), on(car, 11, 0), off(car, 11, 4))
        assertNull(vehicleFor(same, 0, 10))
    }

    @Test fun `of two connected vehicles the longer connected wins`() {
        val events = listOf(on(car, 10, 0), off(car, 10, 6), on(van, 20, 0), off(van, 20, 9))
        assertEquals(van, vehicleFor(events, 0, 10))
    }

    @Test fun `reconnecting within the track adds up`() {
        val events = listOf(on(car, 10, 0), off(car, 10, 3), on(car, 10, 5), off(car, 10, 8))
        assertEquals(car, vehicleFor(events, 0, 10))
    }

    @Test fun `an empty span and an empty log name nothing`() {
        assertNull(vehicleFor(listOf(on(car, 10, 0)), 5, 5))
        assertNull(vehicleFor(emptyList(), 0, 10))
    }
}
