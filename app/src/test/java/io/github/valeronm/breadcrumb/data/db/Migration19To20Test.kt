package io.github.valeronm.breadcrumb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
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

    private val fixture = MigrationDb(19, ::createV19Schema)
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
}

/**
 * A v19 database: `tracks`, the table v20 alters, `derived_intervals`, the last table the chain
 * rebuilt, and `places`, which v21 hangs its links off. Hand-written and frozen, copied from the
 * exported `19.json` — v19 is history now. Shared with the shape check at the end of the chain
 * ([Migration20To21Test]), which runs every migration from here.
 */
internal fun createV19Schema(db: SupportSQLiteDatabase) {
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
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`label` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`radiusM` REAL NOT NULL, `category` TEXT)",
    )
}
