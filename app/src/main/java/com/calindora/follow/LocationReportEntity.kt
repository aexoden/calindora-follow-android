package com.calindora.follow

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "location_reports")
data class LocationReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val accuracy: Float,
    val signatureInput: String,
    val body: String,
    val permanentlyFailed: Boolean = false,
    val permanentFailureReason: String = "",
    val submissionAttempts: Int = 0,
    val submittedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface LocationReportDao {
    @Insert suspend fun insert(report: LocationReportEntity): Long

    @Query(
        "SELECT * FROM location_reports WHERE submittedAt = 0 AND permanentlyFailed = false ORDER BY timestamp ASC LIMIT :limit"
    )
    suspend fun getUnsubmittedReports(limit: Int): List<LocationReportEntity>

    @Query(
        "UPDATE location_reports SET permanentlyFailed = true, permanentFailureReason = :reason WHERE id = :id"
    )
    suspend fun markAsPermanentlyFailed(id: Long, reason: String)

    @Query("SELECT COUNT(*) FROM location_reports WHERE permanentlyFailed = true AND permanentFailureReason LIKE '%401:%'")
    suspend fun getAuthFailureCountSuspend(): Int

    @Query("SELECT COUNT(*) FROM location_reports WHERE permanentlyFailed = true AND permanentFailureReason LIKE '%401:%'")
    fun getAuthFailureCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_reports WHERE permanentlyFailed = true")
    fun getPermanentlyFailedReportCount(): Flow<Int>

    @Query("SELECT * FROM location_reports WHERE permanentlyFailed = true ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getPermanentlyFailedReports(limit: Int): List<LocationReportEntity>

    @Query("UPDATE location_reports SET permanentlyFailed = false, permanentFailureReason = '' WHERE permanentlyFailed = true")
    suspend fun resetPermanentlyFailedReports()

    @Query("DELETE FROM location_reports WHERE permanentlyFailed = true")
    suspend fun deletePermanentlyFailedReports()

    @Query("UPDATE location_reports SET submittedAt = :timestamp WHERE id = :id")
    suspend fun markAsSubmitted(id: Long, timestamp: Long)

    @Query("UPDATE location_reports SET submissionAttempts = submissionAttempts + 1 WHERE ID = :id")
    suspend fun incrementSubmissionAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM location_reports WHERE submittedAt = 0")
    fun getUnsubmittedReportCount(): Flow<Int>

    @Query("SELECT MAX(submittedAt) FROM location_reports") fun getLastSubmissionTime(): Flow<Long>

    @Query("DELETE FROM location_reports WHERE submittedAt > 0 AND createdAt < :timestamp")
    suspend fun deleteOldSubmittedReports(timestamp: Long)
}
