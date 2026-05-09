package com.calindora.follow

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.work.Configuration as WorkConfiguration

/**
 * Application entry point.
 *
 * Performs one-time initialization that needs to happen before any activities or services are
 * created and exposes the [AppContainer] that holds application-scoped dependencies.
 */
class FollowApplication : Application(), WorkConfiguration.Provider {
  /** Application-scoped dependency container. */
  val container: AppContainer by lazy { AppContainer(this) }

  private var lastLocales: LocaleList = LocaleList.getEmptyLocaleList()

  override val workManagerConfiguration: WorkConfiguration
    get() = WorkConfiguration.Builder().setWorkerFactory(FollowWorkerFactory(container)).build()

  override fun onCreate() {
    super.onCreate()

    Notifications.ensureChannels(this)
    container.backoffManager.start()
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
