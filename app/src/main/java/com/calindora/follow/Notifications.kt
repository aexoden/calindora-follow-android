package com.calindora.follow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Centralized notification channel IDs and notification IDs. */
object Notifications {
  /** Channel IDs passed to [android.app.NotificationChannel]. */
  object ChannelIds {
    /** Foreground service / location-sharing notifications posted by [FollowService]. */
    const val DEFAULT = "com.calindora.follow.default"

    /** Authentication failure notifications posted by [SubmissionWorker]. */
    const val CREDENTIALS = "com.calindora.follow.credentials"
  }

  /** Notification IDs passed to [android.app.NotificationManager.notify] / cancel. */
  object Ids {
    /** Foreground service notification posted by [FollowService.startForeground]. */
    const val FOREGROUND = 1

    /** Credential-issue notification posted by [SubmissionWorker.notifyCredentialIssue]. */
    const val CREDENTIAL = 38
  }

  /**
   * Register every notification channel the app uses.
   *
   * Intended to be called once from [FollowApplication.onCreate] so the channels are visible in the
   * OS notification settings before any notification fires.
   */
  fun ensureChannels(context: Context) {
    val default =
        NotificationChannel(
                ChannelIds.DEFAULT,
                context.getString(R.string.notification_channel_default_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply {
              description = context.getString(R.string.notification_channel_default_description)
            }

    val credentials =
        NotificationChannel(
                ChannelIds.CREDENTIALS,
                context.getString(R.string.notification_channel_credentials_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            .apply {
              description = context.getString(R.string.notification_channel_credentials_description)
            }

    context.notificationManager.createNotificationChannels(listOf(default, credentials))
  }
}

/** [NotificationManager] for this [Context]. Always non-null on supported API levels. */
val Context.notificationManager: NotificationManager
  get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
