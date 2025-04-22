package com.calindora.follow

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Settings screen */
data class SettingsUiState(
    val serviceUrl: String = "",
    val deviceKey: String = "",
    val deviceSecret: String = "",
    val isCredentialBlocked: Boolean = false,
    val authFailureCount: Int = 0,
    val failedReportCount: Int = 0,
    val showResetDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val toastMessage: String? = null,
)

/** ViewModel for the Settings screen */
class SettingsViewModel(application: Application) : ViewModel() {
    private val locationReportDao = AppDatabase.getInstance(application).locationReportDao()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

    private val settingsRepository = SettingsRepository(application, locationReportDao)

    private val _uiState =
        MutableStateFlow(
            SettingsUiState(
                serviceUrl =
                    prefs.getString("preference_url", "https://follow.calindora.com")
                        ?: "https://follow.calindora.com",
                deviceKey = prefs.getString("preference_device_key", "") ?: "",
                deviceSecret = prefs.getString("preference_device_secret", "") ?: "",
                isCredentialBlocked =
                    prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false),
            )
        )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            locationReportDao.getAuthFailureCount().collect { count ->
                _uiState.update { it.copy(authFailureCount = count) }
            }
        }

        viewModelScope.launch {
            locationReportDao.getPermanentlyFailedReportCount().collect { count ->
                _uiState.update { it.copy(failedReportCount = count) }
            }
        }
    }

    // Settings update functions
    fun updateServiceUrl(url: String) {
        _uiState.update { it.copy(serviceUrl = url) }
        prefs.edit { putString("preference_url", url) }
    }

    fun updateDeviceKey(key: String) {
        _uiState.update { it.copy(deviceKey = key) }
        prefs.edit { putString("preference_device_key", key) }
    }

    fun updateDeviceSecret(secret: String) {
        _uiState.update { it.copy(deviceSecret = secret) }
        prefs.edit { putString("preference_device_secret", secret) }
    }

    fun updateCredentialBlockedStatus(isBlocked: Boolean) {
        _uiState.update { it.copy(isCredentialBlocked = isBlocked) }
    }

    // Dialog management
    fun showResetDialog() {
        _uiState.update { it.copy(showResetDialog = true) }
    }

    fun dismissResetDialog() {
        _uiState.update { it.copy(showResetDialog = false) }
    }

    fun showExportDialog() {
        _uiState.update { it.copy(showExportDialog = true) }
    }

    fun dismissExportDialog() {
        _uiState.update { it.copy(showExportDialog = false) }
    }

    fun showDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun clearToastMessage() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // Action functions
    fun resetCredentialBlock() {
        viewModelScope.launch {
            val success = settingsRepository.resetCredentialBlock()

            _uiState.update {
                it.copy(
                    showResetDialog = false,
                    toastMessage =
                        if (success) {
                            "Authentication block reset. Retrying submissions."
                        } else {
                            "Failed to reset authentication block."
                        },
                )
            }
        }
    }

    fun exportFailedReports() {
        viewModelScope.launch {
            val success = settingsRepository.exportFailedReports()

            _uiState.update {
                it.copy(
                    showExportDialog = false,
                    toastMessage =
                        if (success) {
                            "Failed reports exported to logs directory"
                        } else {
                            "Failed to export reports"
                        },
                )
            }
        }
    }

    fun deleteFailedReports() {
        viewModelScope.launch {
            val success = settingsRepository.deleteFailedReports()

            _uiState.update {
                it.copy(
                    showDeleteDialog = false,
                    toastMessage =
                        if (success) {
                            "Failed reports deleted successfully"
                        } else {
                            "Failed to delete reports"
                        },
                )
            }
        }
    }
}

/** Factory for creating SettingsViewModel with the Application context */
class SettingsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/** Repository to handle settings-related data operations */
class SettingsRepository(
    private val context: Context,
    private val locationReportDao: LocationReportDao,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    suspend fun resetCredentialBlock(): Boolean =
        runCatching {
                locationReportDao.resetPermanentlyFailedReports()

                prefs.edit { putBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false) }

                val workRequest = OneTimeWorkRequestBuilder<SubmissionWorker>().build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "settings_reset_retry",
                        ExistingWorkPolicy.REPLACE,
                        workRequest,
                    )

                true
            }
            .getOrDefault(false)

    suspend fun exportFailedReports(): Boolean =
        runCatching { SubmissionWorker.exportFailedReports(context) }.getOrDefault(false)

    suspend fun deleteFailedReports(): Boolean =
        runCatching {
                locationReportDao.deletePermanentlyFailedReports()
                true
            }
            .getOrDefault(false)
}
