package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the shape [PlaceAddresses.parse] expects of Photon's reverse lookup, with no network near. */
class PlaceAddressesTest {

    @Test fun `reads the first feature's street and number`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-2.0,1.0]},
               "properties":{"street":"Teststraße","housenumber":"7","city":"Testtown",
                             "countrycode":"DE","osm_key":"building","extent":[1,2,3,4]}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-2.1,1.1]},
               "properties":{"street":"Other Street","housenumber":"1","countrycode":"DE"}}
            ]}
        """.trimIndent()

        assertEquals("Teststraße 7", PlaceAddresses.parse(json.reader()))
    }

    @Test fun `an empty answer is no address`() {
        assertNull(PlaceAddresses.parse("""{"type":"FeatureCollection","features":[]}""".reader()))
    }

    @Test fun `asks for the one nearest feature at the pin`() {
        assertEquals(
            "https://photon.komoot.io/reverse?limit=1&lat=1.0&lon=-2.0",
            PlaceAddresses.url(Coordinate(1.0, -2.0)),
        )
    }
}
