package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v22 adds `seen_networks`, one empty table whose suggestion points at `places`. Additive, so what
 * there is to carry is the places — and a place going must leave the network, not take it along.
 */
@RunWith(RobolectricTestRunner::class)
class Migration21To22Test {

    /** The one v21 table this migration reaches. Hand-written and frozen, from the exported `21.json`. */
    private fun v21() = MigrationDb(21) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`label` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`radiusM` REAL NOT NULL, `category` TEXT)",
        )
    }

    @Test fun `the places come across, and a network outlives the place it was seen at`() {
        val fixture = v21()
        try {
            val db = fixture.db
            db.execSQL(
                "INSERT INTO places (id, label, lat, lon, createdAt, radiusM, category) " +
                    "VALUES (3, 'Home', 1.0, -2.0, 1000, 75.0, NULL)",
            )

            AppDatabase.MIGRATION_21_22.migrate(db)

            db.query("SELECT label FROM places WHERE id = 3").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Home", c.getString(0))
            }
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO seen_networks (ssid, lastSeenAt, placeId) VALUES ('Net', 5000, 3)")
            db.execSQL("DELETE FROM places WHERE id = 3")
            db.query("SELECT lastSeenAt, placeId FROM seen_networks WHERE ssid = 'Net'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(5000L, c.getLong(0))
                assertTrue(c.isNull(1))
            }
        } finally {
            fixture.close()
        }
    }

    /**
     * **The guard a real upgrade is exposed to**, and this is where it belongs: Room compares what
     * it finds against its entities on the first open after an upgrade, and only the *end* of the
     * chain is ever compared that way — which is here. So this runs the chain from the oldest
     * schema any install can be on, every migration in turn, and compares every table one of them
     * wrote. [TableInfo] is the shape Room compares, rather than the `CREATE` text, so formatting is
     * not mistaken for drift.
     *
     * Move it into the next migration's test when one lands, for the same reason it sits here.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the migrated tables are the shape Room builds from the entities`() {
        val fixture = MigrationDb(19, ::createV19Schema)
        try {
            val db = fixture.db
            AppDatabase.MIGRATION_19_20.migrate(db)
            AppDatabase.MIGRATION_20_21.migrate(db)
            AppDatabase.MIGRATION_21_22.migrate(db)

            val room = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                val generated = room.openHelper.writableDatabase
                val tables = listOf(
                    "tracks", "derived_intervals", "places",
                    "vehicles", "vehicle_links", "link_connections", "place_links", "seen_networks",
                )
                for (table in tables) {
                    assertEquals(table, TableInfo.read(generated, table), TableInfo.read(db, table))
                }
            } finally {
                room.close()
            }
        } finally {
            fixture.close()
        }
    }
}
