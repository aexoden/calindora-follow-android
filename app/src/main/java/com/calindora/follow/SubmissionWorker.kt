package com.calindora.follow

import android.app.NotificationChannel
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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.Response

private const val CREDENTIAL_NOTIFICATION_CHANNEL_ID = "com.calindora.follow.credentials"

private val LOG_FILE_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault())

sealed class SubmissionResult {
  data object Success : SubmissionResult()

  data class TransientError(val errorCode: Int, val errorMessage: String) : SubmissionResult()

  data class PermanentError(val errorCode: Int, val errorMessage: String) : SubmissionResult()
}

private data class SubmissionConfig(
    val deviceKey: String,
    val secret: String,
    val api: FollowApi,
)

class CredentialResetReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pendingResult = goAsync()

    CoroutineScope(Dispatchers.IO).launch {
      try {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
          putBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
          putInt(SubmissionWorker.PREF_CONSECUTIVE_AUTH_FAILURES, 0)
        }

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                SubmissionWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                SubmissionWorker.buildWorkRequest(expedited = true),
            )

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(SubmissionWorker.CREDENTIAL_NOTIFICATION_ID)
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

class SubmissionWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
  private val locationReportDao = AppDatabase.getInstance(applicationContext).locationReportDao()
  private val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

  companion object {
    const val PREF_SUBMISSIONS_BLOCKED = "submissions_blocked_credential_issue"
    const val PREF_CONSECUTIVE_AUTH_FAILURES = "consecutive_auth_failures"
    const val UNIQUE_WORK_NAME = "submission_work"
    const val CREDENTIAL_NOTIFICATION_ID = 38

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

    suspend fun exportFailedReports(context: Context): Boolean {
      val dao = AppDatabase.getInstance(context).locationReportDao()
      val reports = dao.getPermanentlyFailedReports(Int.MAX_VALUE)

      if (reports.isEmpty()) return false

      try {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return false

        val file =
            File(
                context.getExternalFilesDir("logs"),
                "failed_reports_${LOG_FILE_FORMATTER.format(Instant.now())}.log",
            )

        withContext(Dispatchers.IO) {
          BufferedWriter(FileWriter(file)).use { writer ->
            for (report in reports) {
              writer.write("Report ID: ${report.id}\n")
              writer.write("Created At: ${Instant.ofEpochMilli(report.createdAt)}\n")
              writer.write("Timestamp: ${report.timestamp}\n")
              writer.write("Latitude: ${report.latitude}\n")
              writer.write("Longitude: ${report.longitude}\n")
              writer.write("Altitude: ${report.altitude}\n")
              writer.write("Speed: ${report.speed}\n")
              writer.write("Bearing: ${report.bearing}\n")
              writer.write("Accuracy: ${report.accuracy}\n")
              writer.write("Failure Code: ${report.permanentFailureCode}\n")
              writer.write("Failure Reason: ${report.permanentFailureReason}\n")
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

        val authFailureCount = preferences.getInt(PREF_CONSECUTIVE_AUTH_FAILURES, 0)
        if (authFailureCount >= Config.Submission.MAX_AUTH_FAILURES) {
          preferences.edit(commit = true) { putBoolean(PREF_SUBMISSIONS_BLOCKED, true) }
          notifyCredentialIssue(authFailureCount)
          return@withContext Result.failure(workDataOf("error_reason" to "CREDENTIAL_ISSUE"))
        }

        val submissionConfig =
            when (val result = getSubmissionConfig()) {
              is ConfigResult.Valid -> result.config
              is ConfigResult.Invalid -> {
                Log.w("SubmissionWorker", "Invalid configuration: ${result.reason}")
                return@withContext Result.failure(
                    workDataOf("error_reason" to "INVALID_CONFIG", "details" to result.reason)
                )
              }
            }

        try {
          var reports = locationReportDao.getUnsubmittedReports(Config.Submission.MAX_BATCH_SIZE)
          var processedAllReports = true
          var continueSubmission = true

          while (continueSubmission && reports.isNotEmpty()) {
            for (report in reports) {
              when (val result = submitSingleReport(report, submissionConfig)) {
                is SubmissionResult.Success -> {
                  locationReportDao.markAsSubmitted(
                      report.id,
                      System.currentTimeMillis(),
                  )

                  if (preferences.getInt(PREF_CONSECUTIVE_AUTH_FAILURES, 0) != 0) {
                    preferences.edit(commit = true) { putInt(PREF_CONSECUTIVE_AUTH_FAILURES, 0) }
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
                is SubmissionResult.TransientError -> {
                  val isNetworkLevel = result.errorCode == -1

                  if (!isNetworkLevel) {
                    locationReportDao.incrementSubmissionAttempts(report.id)
                    val newAttempts = report.submissionAttempts + 1

                    if (newAttempts >= Config.Submission.MAX_ATTEMPTS) {
                      // This report has failed too many times, so it's probable that it's an issue
                      // with the report itself. We'll mark it as permanently failed, and if it does
                      // turn out to be a temporary issue with the server, the user can reset
                      // reports
                      // from the settings screen to retry. Review this for a future v2 of the API.
                      locationReportDao.markAsPermanentlyFailed(
                          report.id,
                          result.errorCode,
                          "Exceeded max attempts (${Config.Submission.MAX_ATTEMPTS}): ${result.errorMessage}",
                      )
                      processedAllReports = false
                      continue
                    } else {
                      if (result.errorCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        val newCount = preferences.getInt(PREF_CONSECUTIVE_AUTH_FAILURES, 0) + 1
                        preferences.edit(commit = true) {
                          putInt(PREF_CONSECUTIVE_AUTH_FAILURES, newCount)
                        }

                        if (newCount >= Config.Submission.MAX_AUTH_FAILURES) {
                          preferences.edit(commit = true) {
                            putBoolean(PREF_SUBMISSIONS_BLOCKED, true)
                          }
                          notifyCredentialIssue(newCount)
                          return@withContext Result.failure(
                              workDataOf("error_reason" to "CREDENTIAL_ISSUE")
                          )
                        }
                      }
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

    ensureNotificationChannel(notificationManager)

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
        NotificationCompat.Builder(applicationContext, CREDENTIAL_NOTIFICATION_CHANNEL_ID)
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

    notificationManager.notify(CREDENTIAL_NOTIFICATION_ID, builder.build())
  }

  private fun ensureNotificationChannel(notificationManager: NotificationManager) {
    val channel =
        NotificationChannel(
                CREDENTIAL_NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            .apply {
              description = applicationContext.getString(R.string.notification_channel_description)
            }

    notificationManager.createNotificationChannel(channel)
  }

  private fun formatSignature(signatureInput: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), mac.algorithm)
    mac.init(key)

    val digest = mac.doFinal(signatureInput.toByteArray(Charsets.UTF_8))

    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun getSubmissionConfig(): ConfigResult {
    val baseUrl =
        preferences
            .getString(Preferences.KEY_SERVICE_URL, Preferences.DEFAULT_SERVICE_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
    val key = preferences.getString(Preferences.KEY_DEVICE_KEY, "")?.trim().orEmpty()
    val secret = preferences.getString(Preferences.KEY_DEVICE_SECRET, "")?.trim().orEmpty()

    if (baseUrl.isEmpty()) return ConfigResult.Invalid("Service URL is not configured")
    if (key.isEmpty()) return ConfigResult.Invalid("Device key is not configured")
    if (secret.isEmpty()) return ConfigResult.Invalid("Device secret is not configured")

    val parsedUrl =
        try {
          URL(baseUrl)
        } catch (_: java.net.MalformedURLException) {
          return ConfigResult.Invalid("Service URL is not a valid URL")
        }

    if (parsedUrl.protocol != "https") {
      return ConfigResult.Invalid("Service URL must use HTTPS")
    }

    if (parsedUrl.host.isNullOrEmpty()) {
      return ConfigResult.Invalid("Service URL must have a valid host")
    }

    return ConfigResult.Valid(
        SubmissionConfig(deviceKey = key, secret = secret, api = FollowApiFactory.create(baseUrl))
    )
  }

  private suspend fun submitSingleReport(
      report: LocationReportEntity,
      submissionConfig: SubmissionConfig,
  ): SubmissionResult {
    val payload =
        try {
          FollowJson.decodeFromString<LocationReportPayload>(report.body)
        } catch (e: SerializationException) {
          return SubmissionResult.PermanentError(
              0,
              "Stored report body is malformed: ${e.message}",
          )
        }

    val signature = formatSignature(report.signatureInput, submissionConfig.secret)

    return try {
      val response =
          submissionConfig.api.submitReport(submissionConfig.deviceKey, signature, payload)
      mapResponseToResult(response)
    } catch (e: IOException) {
      SubmissionResult.TransientError(-1, e.message ?: "Network error")
    }
  }

  private fun mapResponseToResult(response: Response<Unit>): SubmissionResult {
    val code = response.code()
    if (code == HttpURLConnection.HTTP_CREATED) return SubmissionResult.Success

    // `errorBody().string()` consumes and closes the body, calling it once up-front guarantees we
    // don't leak a connection on branches that don't otherwise read it.
    val errorBody = response.errorBody()?.use { it.string() }.orEmpty()

    return when (code) {
      HttpURLConnection.HTTP_UNAUTHORIZED -> {
        // BUG: The server doesn't actually ever generate this exact string. The server has no way
        // to distinguish an invalid signature from an incorrect secret, so it reports them all bad
        // with the error "The provided signature was invalid". We don't want to treat all auth
        // failures as permanent, so we are just leaving this bug for now, and will only be able
        // to address it with a future v2 API.
        if (errorBody.contains("invalid signature", ignoreCase = true)) {
          SubmissionResult.PermanentError(
              code,
              "Invalid signature for this report",
          )
        } else {
          SubmissionResult.TransientError(code, errorBody.ifEmpty { "Unauthorized" })
        }
      }

      HttpURLConnection.HTTP_NOT_FOUND -> {
        SubmissionResult.PermanentError(code, "Unknown API key")
      }

      HttpURLConnection.HTTP_BAD_REQUEST,
      HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
      422 -> {
        SubmissionResult.PermanentError(
            code,
            errorBody.ifEmpty { "Client error $code" },
        )
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
}
