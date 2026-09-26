package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.core.content.edit
import io.github.valeronm.breadcrumb.data.export.JsonPullReader
import io.github.valeronm.breadcrumb.domain.AddressLine
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The street address of a place's pin, for the Places list — what a row says where the name alone
 * doesn't tell two places apart. Photon's reverse lookup, the same OpenStreetMap service the place
 * search uses, so it sits behind that search's Privacy switch and a switch of its own
 * ([Settings.showPlaceAddresses], off until the user turns it on): the pin's coordinate leaves the
 * device. Answers are kept on the phone, keyed by the pin to ~1 m, so a list scrolled again asks
 * nothing; lookups run one at a time and spaced out, the service being a shared one.
 *
 * Every failure reads as "no address", and the row simply says what it said before.
 */
object PlaceAddresses {

    private const val TAG = "Breadcrumb"
    private const val PREFS = "place_addresses"
    private const val TIMEOUT_MS = 4_000
    private const val SPACING_MS = 1_000L

    /** Stored for a pin the service had no address for, so it isn't asked again. */
    private const val NONE = ""

    private val lock = Mutex()

    /** Whether the list should show addresses at all — both switches on. */
    fun enabled(context: Context): Boolean =
        Settings.isOnlinePlaceSearch(context) && Settings.showPlaceAddresses(context)

    /** The address already looked up for [pin], without asking the network; null when there is none yet. */
    fun cached(context: Context, pin: Coordinate): String? =
        prefs(context).getString(key(pin), null)?.ifEmpty { null }

    /** The address of [pin], looked up once and kept; null when disabled, unknown or unreachable. */
    suspend fun addressOf(context: Context, pin: Coordinate): String? {
        if (!enabled(context)) return null
        val key = key(pin)
        prefs(context).getString(key, null)?.let { return it.ifEmpty { null } }
        return lock.withLock {
            // Another row may have asked for the same pin while this one waited.
            prefs(context).getString(key, null)?.let { return@withLock it.ifEmpty { null } }
            val found = withContext(Dispatchers.IO) { fetch(pin) } ?: return@withLock null
            prefs(context).edit { putString(key, found.line ?: NONE) }
            delay(SPACING_MS)
            found.line
        }
    }

    /** Drops every kept address — the switch turned off, so nothing it fetched lingers. */
    fun clear(context: Context) = prefs(context).edit { clear() }

    /** A lookup that reached the service: [line] is null when it knew no address there. */
    private class Found(val line: String?)

    private fun fetch(pin: Coordinate): Found? = try {
        val connection = URL(url(pin)).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        Found(connection.inputStream.bufferedReader().use(::parse))
    } catch (e: IOException) {
        DebugLog.i(TAG, "address lookup failed: ${e.message}")
        null
    } catch (e: IllegalStateException) {
        DebugLog.i(TAG, "address lookup unparseable: ${e.message}")
        null
    }

    internal fun url(pin: Coordinate): String =
        "https://photon.komoot.io/reverse?limit=1&lat=${pin.lat}&lon=${pin.lon}"

    private fun key(pin: Coordinate): String = String.format(Locale.ROOT, "%.5f,%.5f", pin.lat, pin.lon)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The first feature's address line, or null when the response holds none. */
    internal fun parse(reader: Reader): String? {
        var line: String? = null
        val json = JsonPullReader(reader)
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "features" -> line = parseFeatures(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        json.expectEnd()
        return line
    }

    /** The first feature's line; the rest are read past. */
    private fun parseFeatures(json: JsonPullReader): String? {
        var line: String? = null
        json.beginArray()
        while (json.hasNext()) {
            val parsed = parseFeature(json)
            if (line == null) line = parsed
        }
        json.endArray()
        return line
    }

    private fun parseFeature(json: JsonPullReader): String? {
        var line: String? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "properties" -> line = parseProperties(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return line
    }

    private fun parseProperties(json: JsonPullReader): String? {
        val fields = mutableMapOf<String, String?>()
        json.beginObject()
        while (json.hasNext()) {
            when (val name = json.nextName()) {
                "street", "housenumber", "name", "city", "district", "locality", "countrycode" ->
                    fields[name] = json.nextStringOrNull()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return AddressLine.of(
            street = fields["street"],
            houseNumber = fields["housenumber"],
            name = fields["name"],
            locality = fields["district"] ?: fields["locality"] ?: fields["city"],
            countryCode = fields["countrycode"],
        )
    }
}
