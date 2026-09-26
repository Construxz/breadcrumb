package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressLineTest {

    @Test fun `the number follows the street where the country writes it so`() {
        assertEquals("Teststraße 12", AddressLine.of("Teststraße", "12", "Shop", "Town", "de"))
    }

    @Test fun `the number leads where the country writes it first`() {
        assertEquals("12 Test Street", AddressLine.of("Test Street", "12", null, "Town", "GB"))
    }

    @Test fun `without a number the feature's name says more than the bare street`() {
        assertEquals("Test Station", AddressLine.of("Test Street", null, "Test Station", "Town", "DE"))
        assertEquals("Test Street", AddressLine.of("Test Street", " ", null, "Town", "DE"))
    }

    @Test fun `the locality is the last resort, and nothing at all is null`() {
        assertEquals("Town", AddressLine.of(null, null, null, "Town", null))
        assertNull(AddressLine.of(null, "", " ", null, null))
    }
}
