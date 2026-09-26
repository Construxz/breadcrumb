package io.github.valeronm.breadcrumb.data.geopulse

import android.content.Context
import android.os.SystemClock
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends recorded points to a GeoPulse server as an OwnTracks client would ([OwnTracksHttp]).
 *
 * **The queue is the database.** Nothing is copied aside to be sent: a pass reads the recorder's
 * good points timed after [GeoPulseSettings.sentThroughMs] and moves that mark past each one the
 * server answers for. Being offline for a day therefore costs nothing but the wait, and a process
 * death mid-pass resends at most the point in flight — which GeoPulse's duplicate check absorbs.
 * Only the recorder's own tracks are read: an imported file or a typed trip is history, not a live
 * position, and a merge or split copies points under times already sent.
 *
 * A pass runs when something nudges it — the recorder after each batch of points (at most every
 * [LIVE_INTERVAL_MS]), a track closing, the watchdog's 15-minute alarm, process start, and the
 * Settings button — and stops at the first failure, to be retried by the next nudge. The recorder
 * is a foreground service, which keeps its network through Doze; that is what makes the nudges
 * from its own path enough, and why there is no job scheduler behind them.
 */
object GeoPulseUploader {

    private const val TAG = "GeoPulse"
    private const val BATCH = 200
    private const val TIMEOUT_MS = 15_000
    private const val LIVE_INTERVAL_MS = 30_000L

    /** What the Settings group shows. [failure] is the last pass's, in this process only. */
    data class State(val sentThroughMs: Long = 0L, val sending: Boolean = false, val failure: Failure? = null)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> DebugLog.e(TAG, "upload pass failed", e) },
    )
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastPassAtMs = Long.MIN_VALUE / 2

    private val mutableState = MutableStateFlow(State())

    fun state(context: Context): StateFlow<State> {
        mutableState.update { it.copy(sentThroughMs = GeoPulseSettings.sentThroughMs(context)) }
        return mutableState.asStateFlow()
    }

    /** The recorder's nudge, once per stored batch — cheap to call per second, since it is
     *  throttled before it touches anything but a preference read. */
    fun onPointsRecorded(context: Context) {
        if (SystemClock.elapsedRealtime() - lastPassAtMs < LIVE_INTERVAL_MS) return
        sync(context)
    }

    /** Starts a pass unless one is running or the upload is off. */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!GeoPulseSettings.isEnabled(app)) return
        if (!running.compareAndSet(false, true)) return
        lastPassAtMs = SystemClock.elapsedRealtime()
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
                val batch = dao.pointsAfter(
                    TrackOrigin.RECORDED.code,
                    GeoPulseSettings.sentThroughMs(context),
                    BATCH,
                )
                if (batch.isEmpty()) break
                val result = send(context, connection, batch)
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

    /** Sends [batch] in order, moving the mark past each point the server has answered for. */
    private fun send(context: Context, connection: GeoPulseSettings.Connection, batch: List<TrackPoint>): BatchResult {
        var sent = 0
        for (point in batch) {
            val body = OwnTracksHttp.location(point, System.currentTimeMillis() / 1000)
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
