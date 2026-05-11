// app/src/test/java/com/calindora/follow/SubmissionWorkerExportFailedReportsTest.kt
package com.calindora.follow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubmissionWorkerExportFailedReportsTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `empty failed-report queue is a no-op success even with null logsDir`() = runTest {
    val dao = FakeDao(failedReports = emptyList())

    val result = SubmissionWorker.exportFailedReports(dao, logsDir = null)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `null logsDir with non-empty queue returns failure`() = runTest {
    val dao = FakeDao(failedReports = listOf(sampleReport(id = 1)))

    val result = SubmissionWorker.exportFailedReports(dao, logsDir = null)

    assertTrue(result.isFailure)
  }

  @Test
  fun `writes one record per failed report to a timestamped file in logsDir`() = runTest {
    val dao =
        FakeDao(
            failedReports =
                listOf(sampleReport(id = 1, latitude = 1.0), sampleReport(id = 2, latitude = 2.0)),
        )
    val logsDir = tempFolder.newFolder("logs")

    val result = SubmissionWorker.exportFailedReports(dao, logsDir = logsDir)

    assertTrue(result.isSuccess)
    val files = logsDir.listFiles().orEmpty()
    assertEquals(1, files.size)
    val contents = files[0].readText()
    assertTrue(contents.contains("Report ID: 1"))
    assertTrue(contents.contains("Report ID: 2"))

    // One block per report, separated by "---" lines.
    assertEquals(2, contents.split(Regex("(?m)^---$")).size - 1)

    // Spot-check the field set is present in the first record.
    val firstRecord = contents.substringBefore("---")
    listOf(
            "Report ID: 1",
            "Created At: ",
            "Timestamp: 0",
            "Latitude: 1.0",
            "Longitude: 0.0",
            "Altitude: 0.0",
            "Speed: 0.0",
            "Bearing: 0.0",
            "Accuracy: 0.0",
            "Failure Code: 0",
            "Failure Reason: ",
            "Signature Input: ",
        )
        .forEach { assertTrue("expected '$it' in record:\n$firstRecord", firstRecord.contains(it)) }
  }

  private fun sampleReport(id: Long, latitude: Double = 0.0) =
      LocationReportEntity(
          id = id,
          timestamp = 0L,
          latitude = latitude,
          longitude = 0.0,
          altitude = 0.0,
          speed = 0f,
          bearing = 0f,
          accuracy = 0f,
      )

  private class FakeDao(private val failedReports: List<LocationReportEntity>) : LocationReportDao {
    override suspend fun getPermanentlyFailedReports(limit: Int): List<LocationReportEntity> =
        failedReports

    override fun getPermanentlyFailedReportCount(): Flow<Int> = MutableStateFlow(failedReports.size)

    // Every other DAO method should be unreachable from exportFailedReports.
    override suspend fun insert(report: LocationReportEntity): Long = unused()

    override suspend fun getUnsubmittedReports(limit: Int): List<LocationReportEntity> = unused()

    override suspend fun markAsPermanentlyFailed(id: Long, code: Int, reason: String) = unused()

    override suspend fun retryPermanentlyFailedReports(): Int = unused()

    override suspend fun deletePermanentlyFailedReports() = unused()

    override suspend fun deleteUnsubmittedReports() = unused()

    override suspend fun deleteOldestUnsubmittedReport() = unused()

    override suspend fun markAsSubmitted(id: Long, timestamp: Long) = unused()

    override suspend fun incrementSubmissionAttempts(id: Long) = unused()

    override fun getUnsubmittedReportCount(): Flow<Int> = unused()

    override fun getLastSubmissionTime(): Flow<Long> = unused()

    override suspend fun deleteOldSubmittedReports(timestamp: Long) = unused()

    private fun unused(): Nothing =
        throw NotImplementedError("DAO method not expected during this test")
  }
}
