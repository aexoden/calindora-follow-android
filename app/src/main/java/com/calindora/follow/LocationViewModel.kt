package com.calindora.follow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
        val workRequest =
            OneTimeWorkRequestBuilder<SubmissionWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("manual_sync", ExistingWorkPolicy.REPLACE, workRequest)
    }
}
