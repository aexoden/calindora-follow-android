package com.calindora.follow

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for [LocationReportDao] queries that have real logic. Trivial queries are
 * intentionally not covered.
 */
@RunWith(AndroidJUnit4::class)
class LocationReportDaoTest {
  private lateinit var db: AppDatabase
  private lateinit var dao: LocationReportDao

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    dao = db.locationReportDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun getUnsubmittedReports_excludesSubmittedAndPermanentlyFailed_andOrdersAscending() =
      runBlocking {
        val idA = dao.insert(report(timestamp = 3000))
        val idB = dao.insert(report(timestamp = 1000))
        val idC = dao.insert(report(timestamp = 2000))
        dao.insert(report(timestamp = 500, submittedAt = 9_999)) // submitted -> excluded
        dao.insert(report(timestamp = 250, permanentlyFailed = true)) // failed -> excluded

        val result = dao.getUnsubmittedReports(limit = 100)

        assertEquals(listOf(idB, idC, idA), result.map { it.id })
      }

  @Test
  fun getUnsubmittedReports_respectsLimit() = runBlocking {
    repeat(5) { dao.insert(report(timestamp = it.toLong())) }

    val result = dao.getUnsubmittedReports(limit = 3)

    assertEquals(listOf(0L, 1L, 2L), result.map { it.timestamp })
  }

  @Test
  fun deleteOldestUnsubmittedReport_deletesOnlyTheOldestEligibleRow() = runBlocking {
    // Older but ineligible rows. The subquery must skip these and find the oldest *eligible* row.
    dao.insert(report(timestamp = 100, submittedAt = 5_000))
    val olderPermFailed = dao.insert(report(timestamp = 200, permanentlyFailed = true))
    dao.insert(report(timestamp = 300)) // oldest eligible -> should be deleted
    val middleEligible = dao.insert(report(timestamp = 400))

    dao.deleteOldestUnsubmittedReport()

    assertEquals(listOf(middleEligible), dao.getUnsubmittedReports(limit = 100).map { it.id })
    assertEquals(3, totalRowCount())
    assertEquals(
        listOf(olderPermFailed),
        dao.getPermanentlyFailedReports(limit = 100).map { it.id },
    )
  }

  @Test
  fun deleteOldestUnsubmittedReport_isANoopWhenNoEligibleRowsExist() = runBlocking {
    dao.insert(report(timestamp = 100, submittedAt = 5_000))
    dao.insert(report(timestamp = 200, permanentlyFailed = true))

    dao.deleteOldestUnsubmittedReport()

    assertEquals(2, totalRowCount())
  }

  @Test
  fun getPermanentlyFailedReports_returnsOnlyFailedRowsInDescendingTimestampOrder() = runBlocking {
    val newer = dao.insert(report(timestamp = 3000, permanentlyFailed = true))
    val older = dao.insert(report(timestamp = 1000, permanentlyFailed = true))
    dao.insert(report(timestamp = 2000)) // not failed -> excluded

    val result = dao.getPermanentlyFailedReports(limit = 100)

    assertEquals(listOf(newer, older), result.map { it.id })
  }

  @Test
  fun retryPermanentlyFailedReports_clearsFailureFieldsAndAttempts_onlyOnFailedRows() =
      runBlocking {
        val failedId =
            dao.insert(
                report(
                    timestamp = 1000,
                    permanentlyFailed = true,
                    permanentFailureCode = 401,
                    permanentFailureReason = "HTTP 401: Unauthorized",
                    submissionAttempts = 5,
                )
            )
        val cleanId = dao.insert(report(timestamp = 2000, submissionAttempts = 2))

        val updated = dao.retryPermanentlyFailedReports()

        assertEquals(1, updated)
        val unsubmitted = dao.getUnsubmittedReports(limit = 100).associateBy { it.id }

        val retried = unsubmitted.getValue(failedId)
        assertFalse(retried.permanentlyFailed)
        assertEquals(0, retried.permanentFailureCode)
        assertEquals("", retried.permanentFailureReason)
        assertEquals(0, retried.submissionAttempts)

        // Non-failed row's submissionAttempts must be untouched.
        assertEquals(2, unsubmitted.getValue(cleanId).submissionAttempts)
      }

  @Test
  fun deleteUnsubmittedReports_leavesSubmittedAndPermanentlyFailedRows() = runBlocking {
    dao.insert(report(timestamp = 100))
    dao.insert(report(timestamp = 200))
    dao.insert(report(timestamp = 300, submittedAt = 9_999))
    // permanentlyFailed rows also have submittedAt = 0; the query must still leave them alone.
    dao.insert(report(timestamp = 400, permanentlyFailed = true))

    dao.deleteUnsubmittedReports()

    assertEquals(0, dao.getUnsubmittedReports(limit = 100).size)
    assertEquals(1, dao.getPermanentlyFailedReports(limit = 100).size)
    assertEquals(2, totalRowCount())
  }

  @Test
  fun deleteOldSubmittedReports_doesNotDeleteUnsubmittedRowsBelowCutoff() = runBlocking {
    // submittedAt = 0 here. Without the `submittedAt > 0` guard in the query these would be
    // deleted, because 0 < cutoff is trivially true.
    val unsubmitted = dao.insert(report(timestamp = 100))
    val permFailed = dao.insert(report(timestamp = 200, permanentlyFailed = true))
    dao.insert(report(timestamp = 300, submittedAt = 1_000)) // old submitted -> deleted
    dao.insert(report(timestamp = 400, submittedAt = 5_000)) // at cutoff -> kept (strict <)

    dao.deleteOldSubmittedReports(timestamp = 5_000)

    assertEquals(3, totalRowCount())
    assertTrue(dao.getUnsubmittedReports(limit = 100).any { it.id == unsubmitted })
    assertEquals(
        listOf(permFailed),
        dao.getPermanentlyFailedReports(limit = 100).map { it.id },
    )
  }

  private fun totalRowCount(): Int =
      db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM location_reports").use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
      }

  @Test
  fun getLastSubmissionTime_returnsMaxSubmittedAt_andTreatsUnsubmittedAsZero() = runBlocking {
    dao.insert(report(timestamp = 100)) // submittedAt = 0
    dao.insert(report(timestamp = 200, submittedAt = 7_000))
    dao.insert(report(timestamp = 300, submittedAt = 3_000))

    assertEquals(7_000L, dao.getLastSubmissionTime().first())
  }

  @Test
  fun getLastSubmissionTime_returnsZero_whenTableIsEmpty() = runBlocking {
    // MAX() returns NULL on an empty table; the COALESCE in the query collapses that to 0
    // so the Flow<Long> contract holds during the install-to-first-submission window.
    assertEquals(0L, dao.getLastSubmissionTime().first())
  }

  @Test
  fun getUnsubmittedReportCount_matchesFilter() = runBlocking {
    dao.insert(report(timestamp = 100))
    dao.insert(report(timestamp = 200))
    dao.insert(report(timestamp = 300, submittedAt = 9_999))
    dao.insert(report(timestamp = 400, permanentlyFailed = true))

    assertEquals(2, dao.getUnsubmittedReportCount().first())
  }

  @Test
  fun getPermanentlyFailedReportCount_matchesFilter() = runBlocking {
    dao.insert(report(timestamp = 100))
    dao.insert(report(timestamp = 200, permanentlyFailed = true))
    dao.insert(report(timestamp = 300, permanentlyFailed = true))

    assertEquals(2, dao.getPermanentlyFailedReportCount().first())
  }

  @Test
  fun markAsPermanentlyFailed_setsFlagCodeAndReason() = runBlocking {
    val id = dao.insert(report(timestamp = 100))

    dao.markAsPermanentlyFailed(id, code = 422, reason = "validation")

    val failed = dao.getPermanentlyFailedReports(limit = 100).single()
    assertEquals(id, failed.id)
    assertTrue(failed.permanentlyFailed)
    assertEquals(422, failed.permanentFailureCode)
    assertEquals("validation", failed.permanentFailureReason)
  }

  private fun report(
      timestamp: Long,
      submittedAt: Long = 0,
      permanentlyFailed: Boolean = false,
      permanentFailureCode: Int = 0,
      permanentFailureReason: String = "",
      submissionAttempts: Int = 0,
  ): LocationReportEntity =
      LocationReportEntity(
          timestamp = timestamp,
          latitude = 47.6588,
          longitude = -117.426,
          altitude = 600.0,
          speed = 1.5f,
          bearing = 90f,
          accuracy = 5f,
          permanentlyFailed = permanentlyFailed,
          permanentFailureCode = permanentFailureCode,
          permanentFailureReason = permanentFailureReason,
          submissionAttempts = submissionAttempts,
          submittedAt = submittedAt,
      )
}
