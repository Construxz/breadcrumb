package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.PlaceLink
import io.github.valeronm.breadcrumb.data.db.SeenNetwork
import io.github.valeronm.breadcrumb.data.db.VehicleLink
import io.github.valeronm.breadcrumb.domain.VehicleLinkKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What a link stands for: a vehicle, or a place. */
sealed interface LinkOwner {
    data class OfVehicle(val vehicleId: Long) : LinkOwner
    data class OfPlace(val placeId: Long) : LinkOwner
}

/** One registered device or network, whichever table holds it. */
data class Connection(val kind: VehicleLinkKind, val key: String, val label: String, val owner: LinkOwner)

/**
 * Every device and network the user tied to something — a vehicle's ([VehicleLink]) and a place's
 * ([PlaceLink]) — read as one list, which is how Settings → Connections shows them. The two tables
 * are kept apart because only a vehicle's links feed a track's verdict and keep a connection log;
 * what this adds is the rule neither table's index can state: one device or network stands for one
 * thing, so moving it to another owner takes it from the first.
 */
class ConnectionRepository(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val vehicleLinks = db.vehicleDao()
    private val placeLinks = db.placeLinkDao()
    private val seen = db.seenNetworkDao()

    fun observeConnections(): Flow<List<Connection>> =
        combine(vehicleLinks.observeLinks(), placeLinks.observeLinks()) { vehicles, places ->
            vehicles.mapNotNull { link ->
                VehicleLinkKind.fromCode(link.kind)?.let {
                    Connection(it, link.key, link.label, LinkOwner.OfVehicle(link.vehicleId))
                }
            } + places.mapNotNull { link ->
                VehicleLinkKind.fromCode(link.kind)?.let {
                    Connection(it, link.key, link.label, LinkOwner.OfPlace(link.placeId))
                }
            }
        }

    /**
     * Ties the device or network to [owner] unless something else already has it — false then, and
     * nothing is written. Adding one the owner already has is a success that changes nothing.
     */
    suspend fun add(kind: VehicleLinkKind, key: String, label: String, owner: LinkOwner): Boolean =
        db.withTransaction {
            val current = ownerOf(kind, key)
            when {
                current == owner -> true
                current != null -> false
                else -> {
                    insert(kind, key, label, owner)
                    true
                }
            }
        }

    /** Ties the device or network to [owner], taking it from whatever held it before. */
    suspend fun assign(kind: VehicleLinkKind, key: String, label: String, owner: LinkOwner) {
        db.withTransaction {
            if (ownerOf(kind, key) == owner) return@withTransaction
            remove(kind, key)
            insert(kind, key, label, owner)
        }
    }

    /** Unties the device or network from whatever holds it. A vehicle link's log goes with it. */
    suspend fun remove(kind: VehicleLinkKind, key: String) {
        db.withTransaction {
            vehicleLinks.deleteLinkByKey(kind.code, key)
            placeLinks.deleteByKey(kind.code, key)
        }
    }

    /** The place links, keyed as the recorder sees them. */
    suspend fun placeLinksByKey(): Map<Pair<VehicleLinkKind, String>, PlaceLink> =
        placeLinks.allLinks().mapNotNull { link ->
            VehicleLinkKind.fromCode(link.kind)?.let { (it to link.key) to link }
        }.toMap()

    /** The Wi-Fi networks the phone connected to lately, newest first — the list to assign from. */
    fun observeSeenNetworks(): Flow<List<SeenNetwork>> = seen.observe()

    /**
     * Notes the phone connecting to [ssid] at [atMs]. [moving] says a track is open, when where the
     * timeline stands is no answer to where the network is; otherwise the place the last track
     * ended in is remembered beside it, as the suggestion the list makes.
     */
    suspend fun noteSeen(ssid: String, atMs: Long, moving: Boolean) {
        db.withTransaction {
            seen.note(ssid, atMs, if (moving) null else seen.lastEndPlaceId())
        }
    }

    /** Drops [ssid] from the list — the user saying it is nothing of theirs. */
    suspend fun forgetSeen(ssid: String) = seen.forget(ssid)

    /** Drops what was last seen before [beforeMs]. */
    suspend fun purgeSeen(beforeMs: Long) = seen.purge(beforeMs)

    /** What the place a link stands for is called, for the notification's line. */
    suspend fun placeLabel(placeId: Long): String? = placeLinks.placeLabel(placeId)

    private suspend fun ownerOf(kind: VehicleLinkKind, key: String): LinkOwner? =
        vehicleLinks.allLinks().firstOrNull { it.kind == kind.code && it.key == key }
            ?.let { LinkOwner.OfVehicle(it.vehicleId) }
            ?: placeLinks.allLinks().firstOrNull { it.kind == kind.code && it.key == key }
                ?.let { LinkOwner.OfPlace(it.placeId) }

    private suspend fun insert(kind: VehicleLinkKind, key: String, label: String, owner: LinkOwner) {
        when (owner) {
            is LinkOwner.OfVehicle ->
                vehicleLinks.insertLink(VehicleLink(vehicleId = owner.vehicleId, kind = kind.code, key = key, label = label))
            is LinkOwner.OfPlace ->
                placeLinks.insertLink(PlaceLink(placeId = owner.placeId, kind = kind.code, key = key, label = label))
        }
    }
}
