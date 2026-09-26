package io.github.valeronm.breadcrumb.data.geopulse

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TEST_START
import io.github.valeronm.breadcrumb.data.TestDb
import io.github.valeronm.breadcrumb.data.db.NO_TRACK
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the GeoPulse upload reads, asked of real Room: which points are queued, and which trip ends
 * a named place holds. The queue is the database, so these queries are the whole of what decides
 * what leaves the phone.
 */
@RunWith(RobolectricTestRunner::class)
class GeoPulseQueueTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao
    private val places = PlaceRepository(ApplicationProvider.getApplicationContext(), test.db)

    @After fun tearDown() = test.close()

    private suspend fun queued(after: Long = 0L) = dao.finishedPointsAfter(TrackOrigin.RECORDED.code, after, 1_000)

    @Test fun `a finished recording is queued in time order, and the mark moves past what was sent`() = runTest {
        val first = test.walk(TEST_START, 0, 5)
        val second = test.walk(TEST_START + 3_600_000L, 0, 5, lonOffset = 0.01)

        val all = queued()
        val good = (dao.allPointsFor(first) + dao.allPointsFor(second)).filterNot { it.ignored }
        assertEquals(good.size, all.size)
        assertEquals(all.map { it.timestamp }.sorted(), all.map { it.timestamp })
        assertEquals(setOf(first, second), all.mapTo(HashSet()) { it.trackId })

        val mid = all.size / 2
        assertEquals(all.drop(mid + 1), queued(after = all[mid].timestamp))
    }

    @Test fun `an open track waits for its close`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })
        assertTrue(queued().isEmpty())

        repository.finishTrack(id, TEST_START + 50_000L)
        assertEquals(dao.allPointsFor(id).count { !it.ignored }, queued().size)
        assertTrue(queued().isNotEmpty())
    }

    @Test fun `a deleted trip and an ignored fix are never sent`() = runTest {
        val kept = test.walk(TEST_START, 0, 5)
        val deleted = test.walk(TEST_START + 3_600_000L, 0, 5, lonOffset = 0.01)
        repository.deleteTrack(deleted)

        val points = queued()
        assertTrue(points.all { it.trackId == kept && !it.ignored })
        assertEquals(dao.allPointsFor(kept).count { !it.ignored }, points.size)
    }

    @Test fun `an imported file is history, not a recording`() = runTest {
        val file = GpxParser.ImportableTrack(
            activityTypeName = "WALKING",
            startedAt = TEST_START,
            endedAt = TEST_START + 50_000L,
            points = (0..5).map { i ->
                TrackPoint(
                    trackId = NO_TRACK, latitude = 1.0 + i * 0.001, longitude = -2.0, altitude = null,
                    accuracy = null, speed = null, bearing = null, timestamp = TEST_START + i * 10_000L,
                )
            },
        )
        assertEquals(1, repository.importTracks(listOf(file)).imported)

        assertTrue(queued().isEmpty())
    }

    @Test fun `a trip end a named place holds carries that name, and the other end none`() = runTest {
        places.create(test.place("Home", 1.0, -2.0))
        val id = test.walk(TEST_START, 0, 5)

        val ends = test.db.placeDao().endPlacesOf(listOf(id))

        assertEquals(1, ends.size)
        assertEquals("Home", ends.single().label)
        assertEquals(queued().first { it.trackId == id }.timestamp, ends.single().atMs)
    }

    @Test fun `the activity is read off the track row`() = runTest {
        val id = test.walk(TEST_START, 0, 5)

        assertEquals(ActivityType.WALKING.name, dao.activityLabels(listOf(id)).single().activityType)
    }
}
