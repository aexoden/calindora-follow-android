package com.calindora.follow

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class LocationViewModel(application: Application) : AndroidViewModel(application) {
  private val repository =
      LocationRepository(application, AppDatabase.getInstance(application).locationReportDao())

  val queueSize: Flow<Int> = repository.queueSize
  val lastSubmissionTime: Flow<Long> = repository.lastSubmissionTime

  fun clearQueue() {
    viewModelScope.launch { repository.clearQueue() }
  }

  fun forceSubmission() {
    repository.forceSubmission()
  }
}

/** Repository for location-tracking data and submission scheduling. */
class LocationRepository(
    private val context: Context,
    private val locationReportDao: LocationReportDao,
) {
  val queueSize: Flow<Int> = locationReportDao.getUnsubmittedReportCount()
  val lastSubmissionTime: Flow<Long> = locationReportDao.getLastSubmissionTime()

  suspend fun clearQueue() {
    locationReportDao.deleteUnsubmittedReports()
  }

  fun forceSubmission() {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            SubmissionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SubmissionWorker.buildWorkRequest(expedited = true),
        )
  }
}
