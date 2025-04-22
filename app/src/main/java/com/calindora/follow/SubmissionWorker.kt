package com.calindora.follow

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CREDENTIAL_NOTIFICATION_ID = 38

sealed class SubmissionResult {
    data object Success : SubmissionResult()

    data class TransientError(val errorCode: Int, val errorMessage: String) : SubmissionResult()

    data class PermanentError(val errorCode: Int, val errorMessage: String) : SubmissionResult()
}

class CredentialResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(context).locationReportDao()
            dao.resetPermanentlyFailedReports()

            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
            }

            val workRequest =
                OneTimeWorkRequestBuilder<SubmissionWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "manual_credential_retry",
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                )

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(CREDENTIAL_NOTIFICATION_ID)
        }
    }
}

class SubmissionWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    private val locationReportDao = AppDatabase.getInstance(applicationContext).locationReportDao()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

    companion object {
        const val MAX_BATCH_SIZE = 50
        const val MAX_AUTH_FAILURES = 5
        const val PREF_SUBMISSIONS_BLOCKED = "submissions_blocked_credential_issue"

        suspend fun exportFailedReports(context: Context): Boolean {
            val dao = AppDatabase.getInstance(context).locationReportDao()
            val reports = dao.getPermanentlyFailedReports(Int.MAX_VALUE)

            if (reports.isEmpty()) return false

            try {
                if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return false

                val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
                val file =
                    File(
                        context.getExternalFilesDir("logs"),
                        "failed_reports_${dateFormat.format(Date())}.log",
                    )

                withContext(Dispatchers.IO) {
                    BufferedWriter(FileWriter(file)).use { writer ->
                        for (report in reports) {
                            writer.write("Report ID: ${report.id}\n")
                            writer.write("Timestamp: ${report.timestamp}\n")
                            writer.write("Latitude: ${report.latitude}\n")
                            writer.write("Longitude: ${report.longitude}\n")
                            writer.write("Altitude: ${report.altitude}\n")
                            writer.write("Speed: ${report.speed}\n")
                            writer.write("Bearing: ${report.bearing}\n")
                            writer.write("Accuracy: ${report.accuracy}\n")
                            writer.write("Failure: ${report.permanentFailureReason}\n")
                            writer.write("Signature Input: ${report.signatureInput}\n\n---\n\n")
                        }
                    }
                }

                return true
            } catch (e: IOException) {
                Log.e("SubmissionWorker", "Failed to export reports", e)
                return false
            }
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (preferences.getBoolean(PREF_SUBMISSIONS_BLOCKED, false)) {
                Log.w("SubmissionWorker", "Submissions blocked due to credential issues")
                return@withContext Result.failure(workDataOf("error_reason" to "CREDENTIAL_ISSUE"))
            }

            val authFailureCount = locationReportDao.getAuthFailureCountSuspend()
            if (authFailureCount >= MAX_AUTH_FAILURES) {
                preferences.edit { putBoolean(PREF_SUBMISSIONS_BLOCKED, true) }
                notifyCredentialIssue(authFailureCount)
                return@withContext Result.failure(workDataOf("error_reason" to "CREDENTIAL_ISSUE"))
            }

            try {
                var reports = locationReportDao.getUnsubmittedReports(MAX_BATCH_SIZE)
                var processedAllReports = true
                var continueSubmission = true

                while (continueSubmission && reports.isNotEmpty()) {
                    for (report in reports) {
                        when (val result = submitSingleReport(report)) {
                            is SubmissionResult.Success -> {
                                locationReportDao.markAsSubmitted(
                                    report.id,
                                    System.currentTimeMillis(),
                                )
                            }
                            is SubmissionResult.PermanentError -> {
                                locationReportDao.markAsPermanentlyFailed(
                                    report.id,
                                    "${result.errorCode}: ${result.errorMessage}",
                                )

                                if (result.errorCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                                    val newAuthFailureCount =
                                        locationReportDao.getAuthFailureCountSuspend()

                                    if (newAuthFailureCount >= MAX_AUTH_FAILURES) {
                                        preferences.edit {
                                            putBoolean(PREF_SUBMISSIONS_BLOCKED, true)
                                        }

                                        notifyCredentialIssue(newAuthFailureCount)

                                        return@withContext Result.failure(
                                            workDataOf("error_reason" to "CREDENTIAL_ISSUE")
                                        )
                                    }
                                }

                                processedAllReports = false
                            }
                            is SubmissionResult.TransientError -> {
                                locationReportDao.incrementSubmissionAttempts(report.id)
                                continueSubmission = false
                                processedAllReports = false
                            }
                        }
                    }

                    if (continueSubmission) {
                        reports = locationReportDao.getUnsubmittedReports(MAX_BATCH_SIZE)
                    }
                }

                // Cleanup old reports
                val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                locationReportDao.deleteOldSubmittedReports(sevenDaysAgo)

                return@withContext if (processedAllReports) {
                    Result.success(workDataOf("submission_time" to System.currentTimeMillis()))
                } else {
                    Result.retry()
                }
            } catch (e: Exception) {
                Log.e("BatchSubmissionWorker", "Error submitting reports", e)
                return@withContext Result.retry()
            }
        }

    private fun notifyCredentialIssue(failureCount: Int) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create a PendingIntent for Settings
        val settingsIntent =
            Intent(applicationContext, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        val settingsPendingIntent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                settingsIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        // Build notification with multiple actions
        val builder =
            NotificationCompat.Builder(applicationContext, "com.calindora.follow.default")
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("Authentication Problem Detected")
                .setContentText(
                    "$failureCount consecutive reports failed authentication. Please check your device key and secret."
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(settingsPendingIntent)
                .addAction(R.drawable.ic_stat_notification, "Check Settings", settingsPendingIntent)
                .setAutoCancel(false)

        notificationManager.notify(CREDENTIAL_NOTIFICATION_ID, builder.build())
    }

    private fun formatSignature(signatureInput: String): String {
        val secret = preferences.getString("preference_device_secret", "") ?: return ""

        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), mac.algorithm)
        mac.init(key)

        val digest = mac.doFinal(signatureInput.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun formatUrl(): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val url = preferences.getString("preference_url", "") ?: return ""
        val key = preferences.getString("preference_device_key", "") ?: return ""
        return String.format("%s/api/v1/devices/%s/reports", url, key)
    }

    private fun submitSingleReport(report: LocationReportEntity): SubmissionResult {
        val url = formatUrl()

        if (url.isEmpty()) {
            return SubmissionResult.PermanentError(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                "Missing API configuration",
            )
        }

        val signature = formatSignature(report.signatureInput)
        var connection: HttpsURLConnection? = null

        return try {
            connection = (URL(url)).openConnection() as? HttpsURLConnection
            connection?.doInput = true
            connection?.doOutput = true
            connection?.connectTimeout = 10000
            connection?.readTimeout = 10000

            connection?.setRequestProperty("Content-Type", "application/json")
            connection?.setRequestProperty("Accept", "application/json")
            connection?.setRequestProperty("X-Signature", signature)

            val out = OutputStreamWriter(connection?.outputStream)
            out.write(report.body)
            out.close()

            val responseCode = connection?.responseCode ?: -1

            when (responseCode) {
                HttpURLConnection.HTTP_CREATED -> SubmissionResult.Success

                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    val errorMessage =
                        connection?.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

                    if (errorMessage.contains("invalid signature", ignoreCase = true)) {
                        SubmissionResult.PermanentError(
                            responseCode,
                            "Invalid signature for this report",
                        )
                    } else {
                        SubmissionResult.PermanentError(responseCode, errorMessage)
                    }
                }

                HttpURLConnection.HTTP_NOT_FOUND -> {
                    SubmissionResult.PermanentError(responseCode, "Unknown API key")
                }

                in 500..599 -> SubmissionResult.TransientError(responseCode, "Server error")

                else -> {
                    SubmissionResult.TransientError(responseCode, "Unexpected error")
                }
            }
        } catch (e: IOException) {
            SubmissionResult.TransientError(-1, e.message ?: "Network error")
        } finally {
            connection?.disconnect()
        }
    }
}
