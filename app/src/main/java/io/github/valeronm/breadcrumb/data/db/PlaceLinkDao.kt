package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The devices and networks that stand for a place. Written from the settings, never per fix. */
@Dao
interface PlaceLinkDao {

    @Query("SELECT * FROM place_links ORDER BY id ASC")
    fun observeLinks(): Flow<List<PlaceLink>>

    @Query("SELECT * FROM place_links")
    suspend fun allLinks(): List<PlaceLink>

    /** -1 when that device or network already stands for a place — the unique index refuses it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: PlaceLink): Long

    @Query("DELETE FROM place_links WHERE id = :id")
    suspend fun deleteLink(id: Long)

    @Query("DELETE FROM place_links WHERE kind = :kind AND `key` = :key")
    suspend fun deleteByKey(kind: String, key: String)

    @Query("SELECT label FROM places WHERE id = :placeId")
    suspend fun placeLabel(placeId: Long): String?
}
