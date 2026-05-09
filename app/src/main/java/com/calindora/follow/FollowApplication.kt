package com.calindora.follow

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList

/**
 * Application entry point.
 *
 * Performs one-time initialization that needs to happen before any activities or services are
 * created.
 */
class FollowApplication : Application() {
  private lateinit var backoffManager: SubmissionBackoffManager
  private var lastLocales: LocaleList = LocaleList.getEmptyLocaleList()

  override fun onCreate() {
    super.onCreate()

    Notifications.ensureChannels(this)

    backoffManager = SubmissionBackoffManager(this)
    backoffManager.start()
  }

  /** Refresh notification channel names and descriptions when the device language changes. */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)

    val newLocales = newConfig.locales
    if (newLocales != lastLocales) {
      lastLocales = newLocales
      Notifications.ensureChannels(this)
    }
  }
}
