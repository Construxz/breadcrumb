package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.LinkConnection
import io.github.valeronm.breadcrumb.data.db.Vehicle
import io.github.valeronm.breadcrumb.data.db.VehicleLink
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import kotlinx.coroutines.flow.Flow

/**
 * The vehicles the user set up, the links that stand for them, and the recorder's log of those
 * links connecting. Which vehicle a finished track was in is decided where the track finishes
 * ([TrackRepository]); this is everything around it.
 */
class VehicleRepository(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val dao = db.vehicleDao()

    fun observeVehicles(): Flow<List<Vehicle>> = dao.observeVehicles()

    fun observeLinks(): Flow<List<VehicleLink>> = dao.observeLinks()

    fun observeVehicleOf(trackId: Long): Flow<Vehicle?> = dao.observeVehicleOf(trackId)

    suspend fun vehicle(id: Long): Vehicle? = dao.vehicle(id)

    suspend fun create(name: String, activity: ActivityType): Long =
        dao.insertVehicle(Vehicle(name = name, activityType = activity.name))

    suspend fun update(id: Long, name: String, activity: ActivityType) =
        dao.updateVehicle(id, name, activity.name)

    /** The tracks travelled in it keep their label and lose only the reference. */
    suspend fun delete(id: Long) = dao.deleteVehicle(id)

    /** False when that device or network already stands for a vehicle or a place. */
    suspend fun addLink(vehicleId: Long, kind: VehicleLinkKind, key: String, label: String): Boolean =
        db.withTransaction {
            val atPlace = db.placeLinkDao().allLinks().any { it.kind == kind.code && it.key == key }
            !atPlace && dao.insertLink(VehicleLink(vehicleId = vehicleId, kind = kind.code, key = key, label = label)) != -1L
        }

    suspend fun removeLink(id: Long) = dao.deleteLink(id)

    /** Every registered link, keyed as the recorder sees them — the filter on what it may log. */
    suspend fun linksByKey(): Map<Pair<VehicleLinkKind, String>, VehicleLink> =
        dao.allLinks().mapNotNull { link ->
            VehicleLinkKind.fromCode(link.kind)?.let { (it to link.key) to link }
        }.toMap()

    /**
     * Records [linkId] connecting or disconnecting at [atMs] — as a change only, so the recorder can
     * report a state it merely re-observed (a restart, a network callback repeating itself) without
     * the log growing or a stretch being cut in two.
     */
    suspend fun logConnection(linkId: Long, atMs: Long, connected: Boolean) {
        db.withTransaction {
            if (dao.lastConnection(linkId)?.connected == connected) return@withTransaction
            dao.insertConnection(LinkConnection(linkId = linkId, atMs = atMs, connected = connected))
        }
    }

    /** Links whose log says connected — what the recorder must close when it stops watching. */
    suspend fun connectedLinkIds(): List<Long> =
        dao.allLinks().map { it.id }.filter { dao.lastConnection(it)?.connected == true }

    /** Drops the log before [beforeMs]; it is only ever read back as far as a track's start. */
    suspend fun purgeConnections(beforeMs: Long) = dao.purgeConnections(beforeMs)

    /** Puts a finished track in [vehicleId], or in none — the user correcting what was detected. */
    suspend fun setTrackVehicle(trackId: Long, vehicleId: Long?) = dao.setTrackVehicle(trackId, vehicleId)

    /** The user retyped [trackId] to [activity]: a vehicle labelled otherwise no longer holds it. */
    suspend fun retyped(trackId: Long, activity: ActivityType) = dao.clearVehicleUnlessOf(trackId, activity.name)
}
