package io.github.valeronm.breadcrumb.data.geopulse

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.geopulse.OwnTracksHttp.Failure
import io.github.valeronm.breadcrumb.data.geopulse.OwnTracksHttp.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pins what [OwnTracksHttp] puts on the wire, with no server near. */
class OwnTracksHttpTest {

    @Test fun `a bare server gets https and the OwnTracks path`() {
        assertEquals("https://geopulse.example.org/api/owntracks", OwnTracksHttp.endpoint("geopulse.example.org"))
        assertEquals("https://geopulse.example.org/api/owntracks", OwnTracksHttp.endpoint(" https://geopulse.example.org/ "))
    }

    @Test fun `the endpoint GeoPulse shows is taken as it is`() {
        assertEquals(
            "http://10.0.0.2:8080/api/owntracks",
            OwnTracksHttp.endpoint("http://10.0.0.2:8080/api/owntracks/"),
        )
    }

    @Test fun `a server under a path keeps it`() {
        assertEquals("https://example.org/geopulse/api/owntracks", OwnTracksHttp.endpoint("https://example.org/geopulse"))
    }

    @Test fun `what cannot name a server is no endpoint`() {
        assertNull(OwnTracksHttp.endpoint(""))
        assertNull(OwnTracksHttp.endpoint("   "))
        assertNull(OwnTracksHttp.endpoint("ftp://example.org"))
        assertNull(OwnTracksHttp.endpoint("https://"))
        assertNull(OwnTracksHttp.endpoint("https://exa mple.org"))
        assertNull(OwnTracksHttp.endpoint("https://example.org/?x=1"))
    }

    @Test fun `basic auth encodes user and password`() {
        val header = OwnTracksHttp.basicAuth("user", "pä:ss")
        assertTrue(header.startsWith("Basic "))
        assertEquals("user:pä:ss", String(Base64.getDecoder().decode(header.removePrefix("Basic ")), Charsets.UTF_8))
    }

    @Test fun `a point becomes a location message in OwnTracks units`() {
        val point = TrackPoint(
            trackId = 1,
            latitude = 1.000123,
            longitude = -2.000456,
            altitude = 12.6,
            accuracy = 4.4f,
            speed = 10f, // 36 km/h
            bearing = 271.5f,
            timestamp = 1_000_000_999L,
            verticalAccuracy = 3.5f,
        )

        val json = OwnTracksHttp.location(point, "DRIVING", poi = null, createdAtSec = 1_000_100L)

        assertEquals(
            "{\"_type\":\"location\",\"lat\":1.000123,\"lon\":-2.000456,\"tst\":1000000," +
                "\"acc\":4,\"alt\":13,\"vel\":36,\"cog\":272,\"vac\":4,\"created_at\":1000100," +
                "\"ext\":{\"track_id\":1,\"activity\":\"DRIVING\"}}",
            json,
        )
    }

    @Test fun `a field the point lacks is left out, not sent as zero`() {
        val point = TrackPoint(
            trackId = 1,
            latitude = 1.0,
            longitude = -2.0,
            altitude = null,
            accuracy = null,
            speed = Float.NaN,
            bearing = null,
            timestamp = 5_000L,
        )

        val json = OwnTracksHttp.location(point, activity = null, poi = null, createdAtSec = 9L)

        assertEquals(
            "{\"_type\":\"location\",\"lat\":1.0,\"lon\":-2.0,\"tst\":5,\"created_at\":9," +
                "\"ext\":{\"track_id\":1}}",
            json,
        )
        listOf("acc", "alt", "vel", "cog", "vac").forEach { assertFalse(it, "\"$it\"" in json) }
    }

    @Test fun `a trip end at a named place carries the name as the poi, escaped`() {
        val point = TrackPoint(
            trackId = 3,
            latitude = 1.0,
            longitude = -2.0,
            altitude = null,
            accuracy = null,
            speed = null,
            bearing = null,
            timestamp = 5_000L,
        )

        val json = OwnTracksHttp.location(point, activity = null, poi = " Café \"Ecke\" ", createdAtSec = 9L)

        assertTrue(json, json.contains(",\"poi\":\"Café \\\"Ecke\\\"\","))
        assertFalse(json, OwnTracksHttp.location(point, null, poi = "  ", createdAtSec = 9L).contains("poi"))
    }

    @Test fun `the first fix after a resume is marked, and a label is escaped`() {
        val point = TrackPoint(
            trackId = 7,
            latitude = 1.0,
            longitude = -2.0,
            altitude = null,
            accuracy = null,
            speed = null,
            bearing = null,
            timestamp = 5_000L,
            segmentStart = true,
        )

        val json = OwnTracksHttp.location(point, "A\"B\\C", poi = null, createdAtSec = 9L)

        assertTrue(json, json.endsWith(",\"ext\":{\"track_id\":7,\"activity\":\"A\\\"B\\\\C\",\"segment_start\":true}}"))
    }

    @Test fun `statuses sort into sent, skipped for good, and retried`() {
        assertEquals(Outcome.Accepted, OwnTracksHttp.outcomeOf(200))
        assertEquals(Outcome.Accepted, OwnTracksHttp.outcomeOf(204))
        assertEquals(Outcome.Refused, OwnTracksHttp.outcomeOf(400))
        assertEquals(Outcome.Failed(Failure.Unauthorized), OwnTracksHttp.outcomeOf(401))
        assertEquals(Outcome.Failed(Failure.Unauthorized), OwnTracksHttp.outcomeOf(403))
        assertEquals(Outcome.Failed(Failure.Http(404)), OwnTracksHttp.outcomeOf(404))
        assertEquals(Outcome.Failed(Failure.Http(503)), OwnTracksHttp.outcomeOf(503))
        assertEquals(Outcome.Failed(Failure.Http(302)), OwnTracksHttp.outcomeOf(302))
    }
}
