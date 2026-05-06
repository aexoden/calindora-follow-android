package com.calindora.follow

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LocationViewModel(application: Application) : AndroidViewModel(application) {
  private val repository =
      LocationRepository(application, AppDatabase.getInstance(application).locationReportDao())

  val queueSize: Flow<Int> = repository.queueSize
  val lastSubmissionTime: Flow<Long> = repository.lastSubmissionTime
  val syncWorkInfo: Flow<WorkInfo?> = repository.syncWorkInfo

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

  /**
   * Latest [WorkInfo] for the unique submission worker, or null if no submission work has been
   * enqueued yet (or it has been pruned).
   */
  val syncWorkInfo: Flow<WorkInfo?> =
      WorkManager.getInstance(context)
          .getWorkInfosForUniqueWorkFlow(SubmissionWorker.UNIQUE_WORK_NAME)
          .map { it.firstOrNull() }

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
