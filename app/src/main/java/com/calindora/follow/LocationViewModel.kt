package com.calindora.follow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class LocationViewModel(application: Application) : AndroidViewModel(application) {
  private val locationReportDao = AppDatabase.getInstance(application).locationReportDao()

  val queueSize = locationReportDao.getUnsubmittedReportCount()
  val lastSubmissionTime = locationReportDao.getLastSubmissionTime()

  fun clearQueue() {
    viewModelScope.launch {
      val reports = locationReportDao.getUnsubmittedReports(Int.MAX_VALUE)
      for (report in reports) {
        locationReportDao.markAsSubmitted(report.id, System.currentTimeMillis())
      }
    }
  }

  fun forceSubmission() {
    WorkManager.getInstance(getApplication())
        .enqueueUniqueWork(
            SubmissionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SubmissionWorker.buildWorkRequest(expedited = true),
        )
  }
}
