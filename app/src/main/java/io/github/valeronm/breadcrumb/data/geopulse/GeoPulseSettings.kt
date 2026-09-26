package io.github.valeronm.breadcrumb.data.geopulse

import android.content.Context
import androidx.core.content.edit

/**
 * The GeoPulse connection: whether to send, where, as whom, and how far the queue has been sent.
 *
 * A file of its own rather than keys in [io.github.valeronm.breadcrumb.data.Settings], because it
 * holds a password: the device-transfer rule carries `settings.xml` whole to the next phone, and a
 * credential has no business riding along with the recorder's tuning. A phone restored by transfer
 * therefore arrives with the upload off, which is also the right answer for a queue whose position
 * would belong to the old phone's recordings.
 */
object GeoPulseSettings {

    private const val FILE = "geopulse"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SENT_THROUGH_MS = "sent_through_ms"
    private const val KEY_SHARE_ACTIVITY = "share_activity"
    private const val KEY_SHARE_PLACES = "share_places"

    /** What GeoPulse files the points under when the user names no device. */
    const val DEFAULT_DEVICE_ID = "breadcrumb"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Switching on starts the queue at this moment: the upload is a live feed of what is recorded
     * while it is on, as OwnTracks is, not an export of the history — GeoPulse imports that from a
     * file. Never moved backwards, so switching off and on again cannot resend what was sent.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = prefs(context)
        prefs.edit {
            putBoolean(KEY_ENABLED, enabled)
            if (enabled) {
                putLong(KEY_SENT_THROUGH_MS, maxOf(sentThroughMs(context), System.currentTimeMillis()))
            }
        }
    }

    fun server(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty()

    fun setServer(context: Context, value: String) = prefs(context).edit { putString(KEY_SERVER, value) }

    fun username(context: Context): String = prefs(context).getString(KEY_USERNAME, "").orEmpty()

    fun setUsername(context: Context, value: String) = prefs(context).edit { putString(KEY_USERNAME, value) }

    fun password(context: Context): String = prefs(context).getString(KEY_PASSWORD, "").orEmpty()

    fun setPassword(context: Context, value: String) = prefs(context).edit { putString(KEY_PASSWORD, value) }

    fun deviceId(context: Context): String = prefs(context).getString(KEY_DEVICE_ID, "").orEmpty()

    fun setDeviceId(context: Context, value: String) = prefs(context).edit { putString(KEY_DEVICE_ID, value) }

    /** Whether a point carries its trip's activity label. The position itself is the feature, so
     *  it has no switch of its own — the upload's switch is that. */
    fun shareActivity(context: Context): Boolean = prefs(context).getBoolean(KEY_SHARE_ACTIVITY, true)

    fun setShareActivity(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_SHARE_ACTIVITY, enabled) }

    /** Whether a trip's ends carry the names of the places holding them. */
    fun sharePlaces(context: Context): Boolean = prefs(context).getBoolean(KEY_SHARE_PLACES, true)

    fun setSharePlaces(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_SHARE_PLACES, enabled) }

    /** Every recorded point up to this instant (epoch ms, the point's own time) has been sent. */
    fun sentThroughMs(context: Context): Long = prefs(context).getLong(KEY_SENT_THROUGH_MS, 0L)

    fun advanceSentThrough(context: Context, timestampMs: Long) {
        if (timestampMs > sentThroughMs(context)) {
            prefs(context).edit { putLong(KEY_SENT_THROUGH_MS, timestampMs) }
        }
    }

    /** Everything a request needs, read once per pass. */
    class Connection(
        val endpoint: String,
        val username: String,
        val deviceId: String,
        val authorization: String,
    )

    /** Null while the address or the username can't make a request. */
    fun connection(context: Context): Connection? {
        val endpoint = OwnTracksHttp.endpoint(server(context)) ?: return null
        val username = username(context).trim().takeIf { it.isNotEmpty() } ?: return null
        return Connection(
            endpoint = endpoint,
            username = username,
            deviceId = deviceId(context).trim().ifEmpty { DEFAULT_DEVICE_ID },
            authorization = OwnTracksHttp.basicAuth(username, password(context)),
        )
    }
}
