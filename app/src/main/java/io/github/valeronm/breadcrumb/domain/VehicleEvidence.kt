package io.github.valeronm.breadcrumb.domain

/** What a vehicle link is: a Bluetooth device, keyed by its address, or a Wi-Fi network, by name. */
enum class VehicleLinkKind(val code: String) {
    BLUETOOTH("bluetooth"),
    WIFI("wifi"),
    ;

    companion object {
        fun fromCode(code: String): VehicleLinkKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Which vehicle a finished track was travelled in, from the log of the vehicles' links connecting
 * and disconnecting. The label is the user's own statement — they said this device or this network
 * is that car — so this names no carrier the recorder guessed; it only decides whether the track
 * happened while one was connected.
 *
 * **Most of the track, not any of it.** A car's Bluetooth stays in range for the first steps of a
 * walk away from it, and a car's hotspot can reach the pavement, so a brush with a link says
 * nothing; a trip made in the car has it connected nearly throughout. [MIN_SHARE] is the line.
 * A vehicle with several links is connected while any one of them is, so a car answering on both
 * Bluetooth and its hotspot is not counted twice.
 */
object VehicleEvidence {

    /** Share of the track's span a vehicle must be connected for. */
    const val MIN_SHARE = 0.5

    /**
     * How far before a track's start the log is read, to learn what was already connected when it
     * began: a car connects when it starts, which can be minutes before it moves.
     */
    const val LOOKBACK_MS = 6 * 60 * 60 * 1000L

    /** One logged event: [linkId] of vehicle [vehicleId] connected or disconnected at [atMs]. */
    data class Event(val vehicleId: Long, val linkId: Long, val atMs: Long, val connected: Boolean)

    /** The vehicle connected for at least [MIN_SHARE] of `[startedAt, endedAt]`, the longest-
     *  connected one if several are; null when none is, or the span is empty. [events] need not be
     *  sorted. A link whose last event is a connect is taken as still connected. */
    fun vehicleFor(events: List<Event>, startedAt: Long, endedAt: Long): Long? {
        val span = endedAt - startedAt
        if (span <= 0) return null
        return connectedIntervals(events)
            .mapValues { (_, intervals) -> coverage(intervals, startedAt, endedAt) }
            .filterValues { it >= span * MIN_SHARE }
            .maxByOrNull { it.value }
            ?.key
    }

    /**
     * Each vehicle's connected stretches. A link's first event being a disconnect means it was
     * connected before the log's window began, so that stretch opens at the start of time rather
     * than being dropped.
     */
    internal fun connectedIntervals(events: List<Event>): Map<Long, List<Interval>> {
        val out = HashMap<Long, MutableList<Interval>>()
        for (linkEvents in events.groupBy { it.linkId }.values) {
            out.getOrPut(linkEvents.first().vehicleId) { mutableListOf() } += stretchesOf(linkEvents)
        }
        return out
    }

    /** One link's connected stretches, from its events in any order. */
    private fun stretchesOf(linkEvents: List<Event>): List<Interval> {
        val stretches = mutableListOf<Interval>()
        var openAt: Long? = null
        for ((i, event) in linkEvents.sortedBy { it.atMs }.withIndex()) {
            val open = openAt
            when {
                event.connected -> openAt = open ?: event.atMs
                open != null -> stretches += Interval(open, event.atMs)
                i == 0 -> stretches += Interval(Long.MIN_VALUE, event.atMs)
            }
            if (!event.connected) openAt = null
        }
        openAt?.let { stretches += Interval(it, null) }
        return stretches
    }

    /** Milliseconds of `[from, to]` covered by the union of [intervals]. */
    internal fun coverage(intervals: List<Interval>, from: Long, to: Long): Long {
        var covered = 0L
        var reach = from
        for (interval in intervals.sortedBy { it.from }) {
            val start = maxOf(interval.from, reach)
            val end = minOf(interval.until ?: to, to)
            if (end > start) {
                covered += end - start
                reach = end
            }
        }
        return covered
    }

    /** A connected stretch: from [from] until [until], null while still connected. */
    data class Interval(val from: Long, val until: Long?)
}
