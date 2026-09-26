package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** The Wi-Fi networks the recorder saw connect, newest first. Written on a connection, never per fix. */
@Dao
interface SeenNetworkDao {

    @Query("SELECT * FROM seen_networks ORDER BY lastSeenAt DESC")
    fun observe(): Flow<List<SeenNetwork>>

    @Query("SELECT * FROM seen_networks WHERE ssid = :ssid")
    suspend fun get(ssid: String): SeenNetwork?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(network: SeenNetwork)

    /**
     * Notes [ssid] connecting at [atMs]. A place seen now replaces the one remembered; none seen now
     * (a track was open) keeps it, since connecting on the move says nothing about where it belongs.
     */
    @Transaction
    suspend fun note(ssid: String, atMs: Long, placeId: Long?) {
        put(SeenNetwork(ssid = ssid, lastSeenAt = atMs, placeId = placeId ?: get(ssid)?.placeId))
    }

    @Query("DELETE FROM seen_networks WHERE ssid = :ssid")
    suspend fun forget(ssid: String)

    @Query("DELETE FROM seen_networks WHERE lastSeenAt < :beforeMs")
    suspend fun purge(beforeMs: Long)

    /**
     * The named place the most recently ended track ended in — where the timeline stands while
     * nothing is recording. Null when that end is in no named place.
     */
    @Query(
        "SELECT c.placeId FROM cluster_members m JOIN derived_clusters c ON c.id = m.clusterId " +
            "WHERE m.isStart = 0 ORDER BY m.atMs DESC LIMIT 1",
    )
    suspend fun lastEndPlaceId(): Long?
}
