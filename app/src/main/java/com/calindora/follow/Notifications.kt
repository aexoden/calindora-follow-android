package com.calindora.follow

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
}
