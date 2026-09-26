package io.github.valeronm.breadcrumb.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v20 adds vehicles: three new tables and `tracks.vehicleId`. Additive, so there is little to
 * carry, but the track rows must come through the `ALTER TABLE` with nothing but a null beside
 * them — the rows are the whole history.
 */
@RunWith(RobolectricTestRunner::class)
class Migration19To20Test {

    /**
     * A v19 database: `tracks`, the table v20 alters, and `derived_intervals`, which the shape
     * check below covers as the last rebuilt table in the chain. Hand-written and frozen, copied
     * from the exported `19.json` — v19 is history now.
     */
    private val fixture = MigrationDb(19) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`activityType` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `source` TEXT, " +
                "`distanceMeters` REAL NOT NULL, `pointCount` INTEGER NOT NULL, `ignoredCount` INTEGER NOT NULL, " +
                "`startLat` REAL, `startLon` REAL, `endLat` REAL, `endLon` REAL, `discardedAt` INTEGER, " +
                "`discardReason` TEXT, `needsReview` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_startedAt` ON `tracks` (`startedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `derived_intervals` (`type` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, `afterTrackId` INTEGER NOT NULL, `clusterId` INTEGER, `reason` TEXT, " +
                "`fromClusterId` INTEGER, `toClusterId` INTEGER, `fromLat` REAL, `fromLon` REAL, `toLat` REAL, " +
                "`toLon` REAL, PRIMARY KEY(`afterTrackId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_derived_intervals_start` ON `derived_intervals` (`start`)")
    }
    private val db: SupportSQLiteDatabase get() = fixture.db

    @After fun tearDown() = fixture.close()

    @Test fun `the tracks come across whole, in no vehicle`() {
        db.execSQL(
            "INSERT INTO tracks (id, activityType, startedAt, endedAt, source, distanceMeters, pointCount, " +
                "ignoredCount, needsReview) VALUES (7, 'DRIVING', 1000, 2000, 'recorded', 1234.5, 40, 2, 0)",
        )

        AppDatabase.MIGRATION_19_20.migrate(db)

        db.query("SELECT activityType, endedAt, distanceMeters, pointCount, vehicleId FROM tracks WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DRIVING", c.getString(0))
            assertEquals(2000L, c.getLong(1))
            assertEquals(1234.5, c.getDouble(2), 1e-9)
            assertEquals(40, c.getInt(3))
            assertTrue(c.isNull(4))
        }
    }

    /**
     * **The guard a real upgrade is exposed to**, and this is where it belongs: Room compares what
     * it finds against its entities on the first open after an upgrade, and only the *end* of the
     * chain is ever compared that way — which is here. The case above reads values, and so cannot
     * see a nullability, a column type, a key or an index; those live in the hand-written DDL, and
     * get someone a crash on open rather than a failure here. [TableInfo] is the shape Room
     * compares, rather than the `CREATE` text, so formatting is not mistaken for drift.
     *
     * Move it into the next migration's test when one lands, for the same reason it sits here.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the migrated tables are the shape Room builds from the entities`() {
        AppDatabase.MIGRATION_19_20.migrate(db)

        val room = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val generated = room.openHelper.writableDatabase
            for (table in listOf("tracks", "derived_intervals", "vehicles", "vehicle_links", "link_connections")) {
                assertEquals(table, TableInfo.read(generated, table), TableInfo.read(db, table))
            }
        } finally {
            room.close()
        }
    }
}
