package com.calindora.follow

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/** [WorkerFactory] that constructs workers with their dependencies supplied by [AppContainer]. */
class FollowWorkerFactory(private val container: AppContainer) : WorkerFactory() {
  override fun createWorker(
      appContext: Context,
      workerClassName: String,
      workerParameters: WorkerParameters,
  ): ListenableWorker? =
      when (workerClassName) {
        SubmissionWorker::class.java.name ->
            SubmissionWorker(
                appContext = appContext,
                workerParams = workerParameters,
                locationReportDao = container.locationReportDao,
                settingsDataStore = container.settingsDataStore,
                encryptedSecretStore = container.encryptedSecretStore,
                notificationManager = container.notificationManager,
            )
        else -> null
      }
}
