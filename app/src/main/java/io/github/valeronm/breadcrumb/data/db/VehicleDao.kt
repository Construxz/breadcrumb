package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A link's connection event with the vehicle it stands for — what the evidence rule reads. */
data class VehicleConnectionEvent(val vehicleId: Long, val linkId: Long, val atMs: Long, val connected: Boolean)

/**
 * The vehicles, their links, and the connection log. Nothing here is on the path a fix takes: the
 * log is written on a connection changing, which is minutes or hours apart, never per fix.
 */
@Dao
interface VehicleDao {

    @Query("SELECT * FROM vehicles ORDER BY name COLLATE NOCASE ASC")
    fun observeVehicles(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicle_links ORDER BY id ASC")
    fun observeLinks(): Flow<List<VehicleLink>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun vehicle(id: Long): Vehicle?

    @Query("SELECT * FROM vehicle_links")
    suspend fun allLinks(): List<VehicleLink>

    @Insert
    suspend fun insertVehicle(vehicle: Vehicle): Long

    @Query("UPDATE vehicles SET name = :name, activityType = :activityType WHERE id = :id")
    suspend fun updateVehicle(id: Long, name: String, activityType: String)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun deleteVehicleRow(id: Long)

    /** A track keeps its label when its vehicle goes; only the reference is dropped. */
    @Query("UPDATE tracks SET vehicleId = NULL WHERE vehicleId = :vehicleId")
    suspend fun clearVehicle(vehicleId: Long)

    /** Links and their log go with the row, by cascade; the tracks' reference is cleared by hand. */
    @Transaction
    suspend fun deleteVehicle(id: Long) {
        clearVehicle(id)
        deleteVehicleRow(id)
    }

    /** -1 when that device or network already stands for a vehicle — the unique index refuses it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: VehicleLink): Long

    @Query("DELETE FROM vehicle_links WHERE id = :id")
    suspend fun deleteLink(id: Long)

    @Insert
    suspend fun insertConnection(event: LinkConnection)

    /** The latest event of [linkId] — so the log records changes, not repeats of a known state. */
    @Query("SELECT * FROM link_connections WHERE linkId = :linkId ORDER BY atMs DESC, id DESC LIMIT 1")
    suspend fun lastConnection(linkId: Long): LinkConnection?

    /**
     * Every logged event from [fromMs] to [toMs], oldest first, with its vehicle. The caller reaches
     * back before a track's start to learn what was already connected when it began.
     */
    @Query(
        "SELECT l.vehicleId AS vehicleId, c.linkId AS linkId, c.atMs AS atMs, c.connected AS connected " +
            "FROM link_connections c JOIN vehicle_links l ON l.id = c.linkId " +
            "WHERE c.atMs >= :fromMs AND c.atMs <= :toMs ORDER BY c.atMs ASC, c.id ASC",
    )
    suspend fun connectionsBetween(fromMs: Long, toMs: Long): List<VehicleConnectionEvent>

    @Query("DELETE FROM link_connections WHERE atMs < :beforeMs")
    suspend fun purgeConnections(beforeMs: Long)

    @Query("UPDATE tracks SET vehicleId = :vehicleId WHERE id = :trackId")
    suspend fun setTrackVehicle(trackId: Long, vehicleId: Long?)

    /** Drops [trackId]'s vehicle unless it is one labelled [activityType] — a retype away from what
     *  the vehicle is says the track was not in it. */
    @Query(
        "UPDATE tracks SET vehicleId = NULL WHERE id = :trackId AND vehicleId IS NOT NULL " +
            "AND vehicleId NOT IN (SELECT id FROM vehicles WHERE activityType = :activityType)",
    )
    suspend fun clearVehicleUnlessOf(trackId: Long, activityType: String)

    /** The vehicle a track was travelled in, if any — the track detail's line. */
    @Query("SELECT v.* FROM vehicles v JOIN tracks t ON t.vehicleId = v.id WHERE t.id = :trackId")
    fun observeVehicleOf(trackId: Long): Flow<Vehicle?>
}
