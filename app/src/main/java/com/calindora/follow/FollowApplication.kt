package com.calindora.follow

import android.app.Application

/**
 * Application entry point.
 *
 * Performs one-time initialization that needs to happen before any activities or services are
 * created.
 */
class FollowApplication : Application() {
  private lateinit var backoffManager: SubmissionBackoffManager

  override fun onCreate() {
    super.onCreate()

    Notifications.ensureChannels(this)

    backoffManager = SubmissionBackoffManager(this)
    backoffManager.start()
  }
}
