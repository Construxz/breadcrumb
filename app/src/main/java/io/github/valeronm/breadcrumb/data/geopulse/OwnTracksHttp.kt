package io.github.valeronm.breadcrumb.data.geopulse

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64
import kotlin.math.roundToInt

/**
 * The wire half of the GeoPulse upload: OwnTracks' HTTP mode, which is the protocol GeoPulse takes
 * location from. Kept apart from the socket and from Android so the shape is pinned by a host test.
 *
 * One point is one request — GeoPulse's endpoint takes a single `location` object, not an array,
 * exactly as an OwnTracks client posts it.
 */
object OwnTracksHttp {

    /** Where GeoPulse serves OwnTracks' HTTP mode, below the server's own address. */
    const val PATH = "/api/owntracks"

    /**
     * The endpoint for what the user typed as the server address, or null when it cannot name one.
     * Takes the bare server ("geopulse.example.org", which is given https) as readily as the full
     * endpoint GeoPulse's own screen shows, so pasting either works.
     */
    fun endpoint(server: String): String? {
        val trimmed = server.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrEmpty()) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        // Trailing slashes only now, once the address is known to have a host they can't eat into.
        val base = withScheme.trimEnd('/')
        return if (base.endsWith(PATH)) base else base + PATH
    }

    /** The `Authorization` header for the credentials GeoPulse issues an OwnTracks source. */
    fun basicAuth(username: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    /**
     * One recorded point as an OwnTracks `location` message. Units are OwnTracks': seconds for the
     * timestamp, whole metres for accuracy and altitude, km/h for speed, degrees for the course.
     * A field the point lacks is left out rather than sent as zero, which the server would store
     * as a measurement.
     *
     * What OwnTracks has no field for rides in `ext`, which GeoPulse keeps as the point's telemetry
     * (and nothing else does): the track's [activity] as the app labelled it, the track's id so
     * the points of one trip can be told apart, and `segment_start` on the first fix after the
     * recorder resumed across a stop. GeoPulse classifies trips from speed on its own; the label
     * is there to be shown and filtered by, not to steer that. A null [activity] is left out.
     *
     * [poi] names the place this point is at — given on a trip's first or last point when a named
     * place holds that end. GeoPulse turns it into a favourite of that name there, or renames the
     * favourite already covering the spot.
     */
    fun location(point: TrackPoint, activity: String?, poi: String?, createdAtSec: Long): String = buildString {
        append("{\"_type\":\"location\"")
        append(",\"lat\":").append(point.latitude)
        append(",\"lon\":").append(point.longitude)
        append(",\"tst\":").append(point.timestamp / 1000)
        point.accuracy.whole()?.let { append(",\"acc\":").append(it) }
        point.altitude?.toFloat().whole()?.let { append(",\"alt\":").append(it) }
        point.speed?.let { it * KMH_PER_MS }.whole()?.let { append(",\"vel\":").append(it) }
        point.bearing.whole()?.let { append(",\"cog\":").append(it) }
        point.verticalAccuracy.whole()?.let { append(",\"vac\":").append(it) }
        append(",\"created_at\":").append(createdAtSec)
        poi?.takeIf { it.isNotBlank() }?.let { append(",\"poi\":").append(jsonString(it.trim())) }
        append(",\"ext\":{\"track_id\":").append(point.trackId)
        activity?.let { append(",\"activity\":").append(jsonString(it)) }
        if (point.segmentStart) append(",\"segment_start\":true")
        append("}}")
    }

    /** [value] as a JSON string literal — a place name is free text. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private const val KMH_PER_MS = 3.6f

    private fun Float?.whole(): Int? = this?.takeIf { it.isFinite() }?.roundToInt()

    /** What a response status means for the point that drew it. */
    sealed interface Outcome {
        /** Stored (or knowingly dropped as a duplicate, which GeoPulse also answers with 200). */
        data object Accepted : Outcome

        /** The server will never take this message; resending it would stall the queue for good. */
        data object Refused : Outcome

        /** Nothing about this point is wrong — the next attempt may well succeed. */
        data class Failed(val failure: Failure) : Outcome
    }

    /** Why a pass stopped before the queue was empty. */
    sealed interface Failure {
        /** No usable server address or username. */
        data object NotConfigured : Failure

        data object Unauthorized : Failure

        data class Http(val status: Int) : Failure

        /** No answer at all — offline, DNS, TLS, a timeout. */
        data object Unreachable : Failure
    }

    fun outcomeOf(status: Int): Outcome = when (status) {
        in 200..299 -> Outcome.Accepted
        400, 413, 422 -> Outcome.Refused
        401, 403 -> Outcome.Failed(Failure.Unauthorized)
        else -> Outcome.Failed(Failure.Http(status))
    }
}
