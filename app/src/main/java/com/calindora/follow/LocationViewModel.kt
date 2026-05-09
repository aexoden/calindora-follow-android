package com.calindora.follow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LocationViewModel(private val repository: LocationRepository) : ViewModel() {
  val queueSize: Flow<Int> = repository.queueSize
  val lastSubmissionTime: Flow<Long> = repository.lastSubmissionTime
  val syncWorkInfo: Flow<WorkInfo?> = repository.syncWorkInfo

  fun clearQueue() {
    viewModelScope.launch { repository.clearQueue() }
  }

  fun dropOldestUnsubmittedReport() {
    viewModelScope.launch { repository.dropOldestUnsubmittedReport() }
  }

  fun forceSubmission() {
    repository.forceSubmission()
  }

  companion object {
    fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
      initializer { LocationViewModel(container.locationRepository) }
    }
  }
}

/** Repository for location-tracking data and submission scheduling. */
class LocationRepository(
    private val locationReportDao: LocationReportDao,
    private val workManager: WorkManager,
) {
  val queueSize: Flow<Int> = locationReportDao.getUnsubmittedReportCount()
  val lastSubmissionTime: Flow<Long> = locationReportDao.getLastSubmissionTime()

  /**
   * Latest [WorkInfo] for the unique submission worker, or null if no submission work has been
   * enqueued yet (or it has been pruned).
   */
  val syncWorkInfo: Flow<WorkInfo?> =
      workManager.getWorkInfosForUniqueWorkFlow(SubmissionWorker.UNIQUE_WORK_NAME).map {
        it.firstOrNull()
      }

  suspend fun clearQueue() {
    locationReportDao.deleteUnsubmittedReports()
  }

  suspend fun dropOldestUnsubmittedReport() {
    locationReportDao.deleteOldestUnsubmittedReport()
  }

  fun forceSubmission() {
    workManager.enqueueUniqueWork(
        SubmissionWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        SubmissionWorker.buildWorkRequest(expedited = true),
    )
  }
}
