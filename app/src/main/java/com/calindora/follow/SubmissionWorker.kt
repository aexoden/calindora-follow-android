package com.calindora.follow

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.calindora.follow.SubmissionWorker.Companion.OUTPUT_KEY_ERROR_REASON
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.Result as KotlinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

sealed class SubmissionResult {
  data object Success : SubmissionResult()

  /**
   * Server is temporarily unavailable, the network failed, or the response was unexpected. Retry
   * the work later.
   */
  data class TransientError(val errorCode: Int, val errorMessage: String) : SubmissionResult()

  /**
   * The report is malformed or otherwise unprocessable by the server. Most likely a bug in either
   * this application or the server itself. Retrying is unlikely to help, though if it is a bug in
   * the server it could conceivably be fixed.
   */
  data class PermanentError(val errorCode: Int, val errorMessage: String) : SubmissionResult()

  /**
   * The device's configuration is incorrect. No report will succeed until the user fixes the
   * configuration, so we don't blame any individual report for the failure.
   */
  data class ConfigurationError(val errorCode: Int, val errorMessage: String) : SubmissionResult()
}

private data class SubmissionConfig(
    val deviceKey: String,
    val secret: String,
    val api: FollowApi,
)

class CredentialResetReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pendingResult = goAsync()
    val container = context.appContainer

    CoroutineScope(Dispatchers.IO).launch {
      try {
        container.settingsDataStore.edit {
          it[AppPreferences.KEY_SUBMISSIONS_BLOCKED] = false
          it[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] = 0
        }

        container.workManager.enqueueUniqueWork(
            SubmissionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SubmissionWorker.buildWorkRequest(expedited = true),
        )

        container.notificationManager.cancel(Notifications.Ids.CREDENTIAL)
      } finally {
        pendingResult.finish()
      }
    }
  }
}

private sealed class ConfigResult {
  data class Valid(val config: SubmissionConfig) : ConfigResult()

  data class Invalid(val reason: String) : ConfigResult()
}

class SubmissionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val locationReportDao: LocationReportDao,
    private val settingsDataStore: DataStore<Preferences>,
    private val encryptedSecretStore: EncryptedSecretStore,
    private val notificationManager: NotificationManager,
) : CoroutineWorker(appContext, workerParams) {
  companion object {
    const val UNIQUE_WORK_NAME = "submission_work"

    /** Key for the failure reason string in [androidx.work.WorkInfo]. */
    const val OUTPUT_KEY_ERROR_REASON = "error_reason"

    /** Key for additional error context in [androidx.work.WorkInfo]. */
    const val OUTPUT_KEY_DETAILS = "details"

    /** Key for the submission timestamp posted on success in [androidx.work.WorkInfo]. */
    const val OUTPUT_KEY_SUBMISSION_TIME = "submission_time"

    /** Value of [OUTPUT_KEY_ERROR_REASON] when submissions are paused due to auth failures. */
    const val ERROR_REASON_CREDENTIAL_ISSUE = "CREDENTIAL_ISSUE"

    /**
     * Value of [OUTPUT_KEY_ERROR_REASON] when service URL / key / secret are missing or invalid.
     */
    const val ERROR_REASON_INVALID_CONFIG = "INVALID_CONFIG"

    fun buildWorkRequest(expedited: Boolean = false): OneTimeWorkRequest {
      val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

      return OneTimeWorkRequestBuilder<SubmissionWorker>()
          .setConstraints(constraints)
          .setBackoffCriteria(
              BackoffPolicy.LINEAR,
              Config.Submission.BACKOFF_DELAY_MS,
              TimeUnit.MILLISECONDS,
          )
          .apply {
            if (expedited) {
              setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
          }
          .build()
    }

    /**
     * Export permanently-failed reports to a log file in [logsDir].
     *
     * Treats an empty queue as a no-op success. Returns failure if [logsDir] is null (external
     * files directory unavailable) or the write fails.
     */
    suspend fun exportFailedReports(
        locationReportDao: LocationReportDao,
        logsDir: File?,
    ): KotlinResult<Unit> =
        runCatching {
              val reports = locationReportDao.getPermanentlyFailedReports(Int.MAX_VALUE)

              // Nothing to export is treated as a no-op success. This should only happen in rare
              // race conditions, as the action in the UI is gated behind failedReportCount > 0.
              if (reports.isEmpty()) return@runCatching

              if (logsDir == null) {
                throw IOException("External storage is not mounted")
              }

              val file =
                  File(
                      logsDir,
                      "failed_reports_${LOG_FILE_TIMESTAMP.format(Instant.now())}.log",
                  )

              withContext(Dispatchers.IO) {
                file.bufferedWriter().use { writer ->
                  for (report in reports) {
                    writer.append(
                        buildString {
                          appendLine("Report ID: ${report.id}")
                          appendLine("Created At: ${Instant.ofEpochMilli(report.createdAt)}")
                          appendLine("Timestamp: ${report.timestamp}")
                          appendLine("Latitude: ${report.latitude}")
                          appendLine("Longitude: ${report.longitude}")
                          appendLine("Altitude: ${report.altitude}")
                          appendLine("Speed: ${report.speed}")
                          appendLine("Bearing: ${report.bearing}")
                          appendLine("Accuracy: ${report.accuracy}")
                          appendLine("Failure Code: ${report.permanentFailureCode}")
                          appendLine("Failure Reason: ${report.permanentFailureReason}")
                          appendLine("Signature Input: ${report.signatureInput()}")
                          appendLine()
                          appendLine("---")
                          appendLine()
                        }
                    )
                  }
                }
              }
            }
            .onFailure { Log.e("SubmissionWorker", "Failed to export reports", it) }
  }

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        try {
          // Idempotent; covers users who upgrade and have the worker fire before opening Settings.
          encryptedSecretStore.migrateFromLegacyIfNeeded()

          val initialPrefs = settingsDataStore.data.first()

          if (initialPrefs[AppPreferences.KEY_SUBMISSIONS_BLOCKED] == true) {
            Log.w("SubmissionWorker", "Submissions blocked due to credential issues")
            return@withContext Result.failure(
                workDataOf(OUTPUT_KEY_ERROR_REASON to ERROR_REASON_CREDENTIAL_ISSUE)
            )
          }

          val authFailureCount = initialPrefs[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] ?: 0
          if (authFailureCount >= Config.Submission.MAX_AUTH_FAILURES) {
            settingsDataStore.edit { it[AppPreferences.KEY_SUBMISSIONS_BLOCKED] = true }
            notifyCredentialIssue(authFailureCount)
            return@withContext Result.failure(
                workDataOf(OUTPUT_KEY_ERROR_REASON to ERROR_REASON_CREDENTIAL_ISSUE)
            )
          }

          val submissionConfig =
              when (val result = getSubmissionConfig()) {
                is ConfigResult.Valid -> result.config
                is ConfigResult.Invalid -> {
                  Log.w("SubmissionWorker", "Invalid configuration: ${result.reason}")
                  return@withContext Result.failure(
                      workDataOf(
                          OUTPUT_KEY_ERROR_REASON to ERROR_REASON_INVALID_CONFIG,
                          OUTPUT_KEY_DETAILS to result.reason,
                      )
                  )
                }
              }

          var reports = locationReportDao.getUnsubmittedReports(Config.Submission.MAX_BATCH_SIZE)
          var processedAllReports = true
          var continueSubmission = true

          while (continueSubmission && reports.isNotEmpty()) {
            for (report in reports) {
              when (val result = submitSingleReport(report, submissionConfig)) {
                is SubmissionResult.Success -> {
                  locationReportDao.markAsSubmitted(report.id, System.currentTimeMillis())

                  val currentFailures =
                      settingsDataStore.data.first()[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES]
                          ?: 0
                  if (currentFailures != 0) {
                    settingsDataStore.edit { it[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] = 0 }
                  }
                }
                is SubmissionResult.PermanentError -> {
                  locationReportDao.markAsPermanentlyFailed(
                      report.id,
                      result.errorCode,
                      result.errorMessage,
                  )

                  processedAllReports = false
                }
                is SubmissionResult.ConfigurationError -> {
                  // Don't increment submissionAttempts, as we don't ever want these to be marked as
                  // permanently failed.
                  val newCount =
                      (settingsDataStore.data.first()[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES]
                          ?: 0) + 1

                  if (newCount >= Config.Submission.MAX_AUTH_FAILURES) {
                    settingsDataStore.edit {
                      it[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] = newCount
                      it[AppPreferences.KEY_SUBMISSIONS_BLOCKED] = true
                    }
                    notifyCredentialIssue(newCount)
                    return@withContext Result.failure(
                        workDataOf(OUTPUT_KEY_ERROR_REASON to ERROR_REASON_CREDENTIAL_ISSUE)
                    )
                  } else {
                    settingsDataStore.edit {
                      it[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] = newCount
                    }
                  }

                  continueSubmission = false
                  processedAllReports = false
                  break
                }
                is SubmissionResult.TransientError -> {
                  val isNetworkLevel = result.errorCode == -1

                  if (!isNetworkLevel) {
                    locationReportDao.incrementSubmissionAttempts(report.id)
                    val newAttempts = report.submissionAttempts + 1

                    if (newAttempts >= Config.Submission.MAX_ATTEMPTS) {
                      // This report has failed too many times, so it's probable that it's an issue
                      // with the report itself. We'll mark it as permanently failed, and if it does
                      // turn out to be a temporary issue with the server, the user can reset
                      // reports from the settings screen to retry. Review this for a future v2 of
                      // the API.
                      locationReportDao.markAsPermanentlyFailed(
                          report.id,
                          result.errorCode,
                          "Exceeded max attempts (${Config.Submission.MAX_ATTEMPTS}): ${result.errorMessage}",
                      )
                      processedAllReports = false
                      continue
                    }
                  }

                  continueSubmission = false
                  processedAllReports = false
                  break
                }
              }
            }

            if (continueSubmission) {
              reports = locationReportDao.getUnsubmittedReports(Config.Submission.MAX_BATCH_SIZE)
            }
          }

          // Cleanup old reports
          val cutoff = System.currentTimeMillis() - Config.Retention.SUBMITTED_REPORT_TTL_MS
          locationReportDao.deleteOldSubmittedReports(cutoff)

          if (processedAllReports) {
            Result.success(workDataOf(OUTPUT_KEY_SUBMISSION_TIME to System.currentTimeMillis()))
          } else {
            Result.retry()
          }
        } catch (e: Exception) {
          Log.e("SubmissionWorker", "Error during submission work", e)
          Result.retry()
        }
      }

  private fun notifyCredentialIssue(failureCount: Int) {
    // Tapping the notification goes to Settings where the user can update their credentials
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

    // "Reset" action goes to CredentialResetReceiver which clears the block, re-enqueues
    // submission work, and dismisses the notification.
    val resetIntent = Intent(applicationContext, CredentialResetReceiver::class.java)
    val resetPendingIntent =
        PendingIntent.getBroadcast(applicationContext, 1, resetIntent, PendingIntent.FLAG_IMMUTABLE)

    // Build notification with multiple actions
    val builder =
        NotificationCompat.Builder(applicationContext, Notifications.ChannelIds.CREDENTIALS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_credential_title))
            .setContentText(
                applicationContext.resources.getQuantityString(
                    R.plurals.notification_credential_text,
                    failureCount,
                    failureCount,
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(settingsPendingIntent)
            .addAction(
                R.drawable.check_24px,
                applicationContext.getString(R.string.notification_action_reset),
                resetPendingIntent,
            )
            .addAction(
                R.drawable.settings_24px,
                applicationContext.getString(R.string.notification_action_settings),
                settingsPendingIntent,
            )
            .setAutoCancel(false)

    notificationManager.notify(Notifications.Ids.CREDENTIAL, builder.build())
  }

  private suspend fun getSubmissionConfig(): ConfigResult {
    val prefs = settingsDataStore.data.first()
    val rawUrl = prefs[AppPreferences.KEY_SERVICE_URL] ?: AppPreferences.DEFAULT_SERVICE_URL
    val key = prefs[AppPreferences.KEY_DEVICE_KEY]?.trim().orEmpty()
    val secret = encryptedSecretStore.get()?.trim().orEmpty()

    validateServiceUrl(rawUrl)?.let {
      return ConfigResult.Invalid(it.description)
    }
    if (key.isEmpty()) return ConfigResult.Invalid("Device key is not configured")
    if (secret.isEmpty()) return ConfigResult.Invalid("Device secret is not configured")

    val baseUrl = rawUrl.trim().trimEnd('/')
    return ConfigResult.Valid(
        SubmissionConfig(deviceKey = key, secret = secret, api = FollowApiFactory.create(baseUrl))
    )
  }

  private suspend fun submitSingleReport(
      report: LocationReportEntity,
      submissionConfig: SubmissionConfig,
  ): SubmissionResult {
    val payload = report.toPayload()
    val signature = hmacSha256Hex(report.signatureInput(), submissionConfig.secret)

    return try {
      val response =
          submissionConfig.api.submitReport(submissionConfig.deviceKey, signature, payload)
      mapResponseToResult(response)
    } catch (e: IOException) {
      SubmissionResult.TransientError(-1, e.message ?: "Network error")
    }
  }
}

internal fun mapResponseToResult(response: Response<Unit>): SubmissionResult {
  val code = response.code()
  if (code == HttpURLConnection.HTTP_CREATED) return SubmissionResult.Success

  // `errorBody().string()` consumes and closes the body, calling it once up-front guarantees we
  // don't leak a connection on branches that don't otherwise read it.
  val errorBody = response.errorBody()?.use { it.string() }.orEmpty()

  return when (code) {
    HttpURLConnection.HTTP_UNAUTHORIZED -> {
      // The v1 API returns 401 for both an incorrect secret (a configuration issue) and an
      // invalid signature (a client/server bug). The server's actual error body is "The provided
      // signature was invalid" in both cases, and it has no way to distinguish them, so we can't
      // route likely bugs to PermanentError reliably. Treat every 401 as a configuration error; a
      // future v2 API should split these into distinct responses.
      SubmissionResult.ConfigurationError(code, errorBody.ifEmpty { "Unauthorized" })
    }

    HttpURLConnection.HTTP_NOT_FOUND -> {
      // Unknown device key or an outright incorrect service URL. No report will succeed until the
      // user fixes the configuration.
      SubmissionResult.ConfigurationError(code, "Unknown device key")
    }

    HttpURLConnection.HTTP_BAD_REQUEST,
    HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
    422 -> {
      SubmissionResult.PermanentError(code, errorBody.ifEmpty { "Client error $code" })
    }

    HttpURLConnection.HTTP_CLIENT_TIMEOUT,
    429,
    in 500..599 -> {
      SubmissionResult.TransientError(code, "Server error")
    }

    else -> {
      SubmissionResult.TransientError(code, "Unexpected error")
    }
  }
}
