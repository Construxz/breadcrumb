package io.github.valeronm.breadcrumb.data.geopulse

import android.content.Context
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.geopulse.OwnTracksHttp.Failure
import io.github.valeronm.breadcrumb.data.geopulse.OwnTracksHttp.Outcome
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends each finished trip to a GeoPulse server, point by point as an OwnTracks client would
 * ([OwnTracksHttp]) — the one GeoPulse ingest that keeps what OwnTracks has no field for, which is
 * how the trip's activity reaches it, and the one that turns a named point into a favourite, which
 * is how the places at its ends do. Each of those rides only while its switch is on.
 *
 * **A trip goes once it is finished and kept.** An open track waits for its close, so what arrives
 * is the settled path: the recorder's overrun at the edges already flagged and left out, and a trip
 * too short to keep never sent. The legs where positioning dropped out are sent as they are — the
 * two points either side of a gap — and GeoPulse draws a trip through them itself, only calling a
 * silence a data gap once it runs for hours.
 *
 * **The queue is the database.** Nothing is copied aside to be sent: a pass reads the good points
 * of finished recorder tracks timed after [GeoPulseSettings.sentThroughMs] and moves that mark past
 * each one the server answers for. Being offline for a day therefore costs nothing but the wait,
 * and a process death mid-pass resends at most the point in flight — which GeoPulse's duplicate
 * check absorbs. Only the recorder's own tracks are read: an imported file or a typed trip is
 * history, not a recording, and a merge or split copies points under times already sent.
 *
 * A pass runs when a track closes, on the watchdog's 15-minute alarm, at process start, and from
 * the Settings button, and stops at the first failure, to be retried by the next of those. The
 * recorder is a foreground service, which keeps its network through Doze; that is what makes those
 * nudges enough, and why there is no job scheduler behind them.
 */
object GeoPulseUploader {

    private const val TAG = "GeoPulse"
    private const val BATCH = 200
    private const val TIMEOUT_MS = 15_000

    /** What the Settings group shows. [failure] is the last pass's, in this process only. */
    data class State(val sentThroughMs: Long = 0L, val sending: Boolean = false, val failure: Failure? = null)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> DebugLog.e(TAG, "upload pass failed", e) },
    )
    private val running = AtomicBoolean(false)

    private val mutableState = MutableStateFlow(State())

    fun state(context: Context): StateFlow<State> {
        mutableState.update { it.copy(sentThroughMs = GeoPulseSettings.sentThroughMs(context)) }
        return mutableState.asStateFlow()
    }

    /**
     * Whether the server takes the connection as configured: null when it does, the reason when it
     * does not. Sends [OwnTracksHttp.PROBE], so it writes nothing on the server, and asks nothing of
     * the upload's switch — a connection is worth checking before it is turned on.
     */
    suspend fun testConnection(context: Context): Failure? = withContext(Dispatchers.IO) {
        val connection = GeoPulseSettings.connection(context.applicationContext)
            ?: return@withContext Failure.NotConfigured
        val failure = when (val outcome = post(connection, OwnTracksHttp.PROBE)) {
            Outcome.Accepted -> null
            Outcome.Refused -> Failure.Http(HttpURLConnection.HTTP_BAD_REQUEST)
            is Outcome.Failed -> outcome.failure
        }
        DebugLog.i(TAG, "connection test: ${failure ?: "ok"}")
        failure
    }

    /** Starts a pass unless one is running or the upload is off. */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!GeoPulseSettings.isEnabled(app)) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                pass(app)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun pass(context: Context) {
        val connection = GeoPulseSettings.connection(context)
        if (connection == null) {
            mutableState.update { it.copy(failure = Failure.NotConfigured) }
            return
        }
        mutableState.update { it.copy(sending = true) }
        val dao = AppDatabase.get(context).trackDao()
        var sent = 0
        var failure: Failure? = null
        try {
            while (failure == null && GeoPulseSettings.isEnabled(context)) {
                val batch = dao.finishedPointsAfter(
                    TrackOrigin.RECORDED.code,
                    GeoPulseSettings.sentThroughMs(context),
                    BATCH,
                )
                if (batch.isEmpty()) break
                val result = send(context, connection, batch, extrasFor(context, batch))
                sent += result.sent
                failure = result.failure
            }
        } finally {
            mutableState.value = State(GeoPulseSettings.sentThroughMs(context), sending = false, failure = failure)
            if (sent > 0 || failure != null) {
                DebugLog.i(TAG, "sent $sent point(s)" + failure?.let { ", stopped: $it" }.orEmpty())
            }
        }
    }

    private class BatchResult(val sent: Int, val failure: Failure?)

    /** What a batch's points carry beyond the fix, each only where the user shares it: the
     *  activity by track, and the place name by track end (keyed on the end fix's time). */
    private class Extras(val activity: Map<Long, String>, val places: Map<Pair<Long, Long>, String>)

    private suspend fun extrasFor(context: Context, batch: List<TrackPoint>): Extras {
        val db = AppDatabase.get(context)
        val trackIds = batch.mapTo(HashSet()) { it.trackId }
        val activity = if (GeoPulseSettings.shareActivity(context)) {
            db.trackDao().activityLabels(trackIds).associate { it.id to it.activityType }
        } else {
            emptyMap()
        }
        val places = if (GeoPulseSettings.sharePlaces(context)) {
            db.placeDao().endPlacesOf(trackIds).associate { (it.trackId to it.atMs) to it.label }
        } else {
            emptyMap()
        }
        return Extras(activity, places)
    }

    /** Sends [batch] in order, moving the mark past each point the server has answered for. */
    private fun send(
        context: Context,
        connection: GeoPulseSettings.Connection,
        batch: List<TrackPoint>,
        extras: Extras,
    ): BatchResult {
        var sent = 0
        for (point in batch) {
            val body = OwnTracksHttp.location(
                point,
                activity = extras.activity[point.trackId],
                poi = extras.places[point.trackId to point.timestamp],
                createdAtSec = System.currentTimeMillis() / 1000,
            )
            when (val outcome = post(connection, body)) {
                Outcome.Accepted -> sent++
                Outcome.Refused -> DebugLog.w(TAG, "server refused point ${point.id}, skipping it")
                is Outcome.Failed -> return BatchResult(sent, outcome.failure)
            }
            GeoPulseSettings.advanceSentThrough(context, point.timestamp)
        }
        return BatchResult(sent, null)
    }

    private fun post(connection: GeoPulseSettings.Connection, body: String): Outcome = try {
        val http = URL(connection.endpoint).openConnection() as HttpURLConnection
        http.requestMethod = "POST"
        http.doOutput = true
        http.instanceFollowRedirects = false
        http.connectTimeout = TIMEOUT_MS
        http.readTimeout = TIMEOUT_MS
        http.setRequestProperty("Content-Type", "application/json")
        http.setRequestProperty("Authorization", connection.authorization)
        // OwnTracks' own headers: GeoPulse files the point under X-Limit-D's device.
        http.setRequestProperty("X-Limit-U", connection.username)
        http.setRequestProperty("X-Limit-D", connection.deviceId)
        val bytes = body.toByteArray(Charsets.UTF_8)
        http.setFixedLengthStreamingMode(bytes.size)
        http.outputStream.use { it.write(bytes) }
        val status = http.responseCode
        // Drained rather than disconnected, so the socket goes back to the pool for the next point.
        (if (status < HttpURLConnection.HTTP_BAD_REQUEST) http.inputStream else http.errorStream)
            ?.use { it.readBytes() }
        OwnTracksHttp.outcomeOf(status)
    } catch (e: IOException) {
        DebugLog.i(TAG, "upload failed: ${e.message}")
        Outcome.Failed(Failure.Unreachable)
    } catch (e: IllegalArgumentException) {
        // A header value the platform refuses to put on the wire — a non-ASCII username or device.
        DebugLog.i(TAG, "upload not sent: ${e.message}")
        Outcome.Failed(Failure.NotConfigured)
    }
}
