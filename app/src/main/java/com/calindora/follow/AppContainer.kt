package com.calindora.follow

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow

/**
 * Application-scoped dependency container.
 *
 * Owned by [FollowApplication]; exposes the app's collaborators so that
 * [androidx.lifecycle.ViewModel]s, [androidx.work.ListenableWorker]s, and [android.app.Service]s
 * can receive their dependencies via constructor parameters or a [FollowWorkerFactory] rather than
 * fetching them ad hoc from a [Context]. This keeps each consumer's collaborators explicit and lets
 * unit tests substitute fakes without spinning up a real [Application].
 *
 * All members are lazy or property getters; nothing is eagerly constructed at app startup.
 */
class AppContainer(private val application: Application) {
  // Storage
  val database: AppDatabase by lazy { AppDatabase.getInstance(application) }

  val locationReportDao: LocationReportDao
    get() = database.locationReportDao()

  val settingsDataStore: DataStore<Preferences>
    get() = application.settingsDataStore

  val encryptedSecretStore: EncryptedSecretStore by lazy { EncryptedSecretStore(application) }

  val credentialStatusFlow: Flow<CredentialStatus>
    get() = application.credentialStatusFlow

  // Android system services
  val workManager: WorkManager
    get() = WorkManager.getInstance(application)

  val notificationManager: NotificationManager
    get() = application.notificationManager

  val connectivityManager: ConnectivityManager?
    get() = application.getSystemService(ConnectivityManager::class.java)

  // Application-wide collaborators
  val backoffManager: SubmissionBackoffManager by lazy {
    SubmissionBackoffManager(connectivityManager, workManager)
  }

  // Repositories and stateless wrappers
  val settingsRepository: SettingsRepository by lazy {
    SettingsRepository(
        application = application,
        locationReportDao = locationReportDao,
        settingsDataStore = settingsDataStore,
        workManager = workManager,
        notificationManager = notificationManager,
    )
  }

  val locationRepository: LocationRepository by lazy {
    LocationRepository(locationReportDao, workManager)
  }
}

/** Convenience accessor for the [AppContainer] from any [Context]. */
val Context.appContainer: AppContainer
  get() = (applicationContext as FollowApplication).container
