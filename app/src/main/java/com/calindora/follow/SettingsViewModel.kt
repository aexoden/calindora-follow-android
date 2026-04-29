package com.calindora.follow

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Settings screen */
data class SettingsUiState(
    val serviceUrl: String = "",
    val deviceKey: String = "",
    val deviceSecret: String = "",
    val isCredentialBlocked: Boolean = false,
    val consecutiveAuthFailures: Int = 0,
    val failedReportCount: Int = 0,
    val showResetDialog: Boolean = false,
    val showRetryDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val toastMessage: ToastMessage? = null,
)

/** ViewModel for the Settings screen */
@OptIn(FlowPreview::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
  private val locationReportDao = AppDatabase.getInstance(application).locationReportDao()
  private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

  private val settingsRepository = SettingsRepository(application, locationReportDao)

  private val credentialPreferenceListener =
      SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        if (
            changedKey == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED ||
                changedKey == SubmissionWorker.PREF_CONSECUTIVE_AUTH_FAILURES
        ) {
          refreshCredentialStatus()
        }
      }

  private val _uiState =
      MutableStateFlow(
          SettingsUiState(
              serviceUrl =
                  prefs.getString(Preferences.KEY_SERVICE_URL, Preferences.DEFAULT_SERVICE_URL)
                      ?: Preferences.DEFAULT_SERVICE_URL,
              deviceKey = prefs.getString(Preferences.KEY_DEVICE_KEY, "") ?: "",
              deviceSecret = prefs.getString(Preferences.KEY_DEVICE_SECRET, "") ?: "",
              isCredentialBlocked =
                  prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false),
              consecutiveAuthFailures =
                  prefs.getInt(SubmissionWorker.PREF_CONSECUTIVE_AUTH_FAILURES, 0),
          )
      )
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()

  init {
    // Persist the default service URL on first run
    if (!prefs.contains(Preferences.KEY_SERVICE_URL)) {
      prefs.edit { putString(Preferences.KEY_SERVICE_URL, Preferences.DEFAULT_SERVICE_URL) }
    }

    refreshCredentialStatus()
    prefs.registerOnSharedPreferenceChangeListener(credentialPreferenceListener)

    // Failed report counts
    viewModelScope.launch {
      locationReportDao.getPermanentlyFailedReportCount().collect { count ->
        _uiState.update { it.copy(failedReportCount = count) }
      }
    }

    // Debounced save: service URL
    viewModelScope.launch {
      _uiState
          .map { it.serviceUrl }
          .distinctUntilChanged()
          .drop(1)
          .debounce(Config.Ui.SAVE_DEBOUNCE_MS)
          .collect { url ->
            prefs.edit { putString(Preferences.KEY_SERVICE_URL, url) }
            _savedEvents.tryEmit(Unit)
          }
    }

    // Debounced save: device key
    viewModelScope.launch {
      _uiState
          .map { it.deviceKey }
          .distinctUntilChanged()
          .drop(1)
          .debounce(Config.Ui.SAVE_DEBOUNCE_MS)
          .collect { key ->
            prefs.edit { putString(Preferences.KEY_DEVICE_KEY, key) }
            _savedEvents.tryEmit(Unit)
          }
    }

    // Debounced save: device secret
    viewModelScope.launch {
      _uiState
          .map { it.deviceSecret }
          .distinctUntilChanged()
          .drop(1)
          .debounce(Config.Ui.SAVE_DEBOUNCE_MS)
          .collect { secret ->
            prefs.edit { putString(Preferences.KEY_DEVICE_SECRET, secret) }
            _savedEvents.tryEmit(Unit)
          }
    }
  }

  private fun refreshCredentialStatus() {
    _uiState.update {
      it.copy(
          isCredentialBlocked = prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false),
          consecutiveAuthFailures =
              prefs.getInt(SubmissionWorker.PREF_CONSECUTIVE_AUTH_FAILURES, 0),
      )
    }
  }

  override fun onCleared() {
    prefs.unregisterOnSharedPreferenceChangeListener(credentialPreferenceListener)
    super.onCleared()
  }

  // Settings update functions - these only update in-memory state
  // The debounced collectors in init will handle saving to SharedPreferences
  fun updateServiceUrl(url: String) {
    _uiState.update { it.copy(serviceUrl = url) }
  }

  fun updateDeviceKey(key: String) {
    _uiState.update { it.copy(deviceKey = key) }
  }

  fun updateDeviceSecret(secret: String) {
    _uiState.update { it.copy(deviceSecret = secret) }
  }

  // Dialog management
  fun showResetDialog() {
    _uiState.update { it.copy(showResetDialog = true) }
  }

  fun dismissResetDialog() {
    _uiState.update { it.copy(showResetDialog = false) }
  }

  fun showRetryDialog() {
    _uiState.update { it.copy(showRetryDialog = true) }
  }

  fun dismissRetryDialog() {
    _uiState.update { it.copy(showRetryDialog = false) }
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
                ToastMessage.Simple(
                    if (success) R.string.toast_credential_reset_success
                    else R.string.toast_credential_reset_failure
                ),
        )
      }
    }
  }

  fun retryFailedReports() {
    viewModelScope.launch {
      val count = _uiState.value.failedReportCount
      val success = settingsRepository.retryFailedReports()

      _uiState.update {
        it.copy(
            showRetryDialog = false,
            toastMessage =
                if (success) {
                  ToastMessage.Plural(R.plurals.toast_reports_queued_for_retry, count)
                } else {
                  ToastMessage.Simple(R.string.toast_retry_failure)
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
                ToastMessage.Simple(
                    if (success) R.string.toast_export_success else R.string.toast_export_failure
                ),
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
                ToastMessage.Simple(
                    if (success) R.string.toast_delete_success else R.string.toast_delete_failure
                ),
        )
      }
    }
  }
}

/** Repository to handle settings-related data operations */
class SettingsRepository(
    private val context: Context,
    private val locationReportDao: LocationReportDao,
) {
  private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

  fun resetCredentialBlock(): Boolean =
      runCatching {
            prefs.edit {
              putBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
              putInt(SubmissionWorker.PREF_CONSECUTIVE_AUTH_FAILURES, 0)
            }

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SubmissionWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    SubmissionWorker.buildWorkRequest(),
                )

            // Cancel the notification if it's showing
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                SubmissionWorker.CREDENTIAL_NOTIFICATION_ID
            )

            true
          }
          .getOrDefault(false)

  suspend fun retryFailedReports(): Boolean =
      runCatching {
            locationReportDao.retryPermanentlyFailedReports()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SubmissionWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    SubmissionWorker.buildWorkRequest(expedited = true),
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
