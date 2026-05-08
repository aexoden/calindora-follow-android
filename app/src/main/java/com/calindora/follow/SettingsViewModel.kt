package com.calindora.follow

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.first
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
    val isLoading: Boolean = true,
)

/** ViewModel for the Settings screen */
@OptIn(FlowPreview::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
  private val locationReportDao = AppDatabase.getInstance(application).locationReportDao()
  private val settingsDataStore = application.settingsDataStore
  private val encryptedSecretStore = EncryptedSecretStore(application)
  private val credentialStatusFlow = application.credentialStatusFlow

  private val settingsRepository = SettingsRepository(application, locationReportDao)

  private val _uiState = MutableStateFlow(SettingsUiState())
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()

  init {
    viewModelScope.launch {
      // Migrate the legacy plaintext secret on first launch. Subsequent calls are no-ops.
      encryptedSecretStore.migrateFromLegacyIfNeeded()

      // Load initial state synchronously before starting observers and save collectors.
      val initialPrefs = settingsDataStore.data.first()
      val initialSecret = encryptedSecretStore.get().orEmpty()

      _uiState.update {
        it.copy(
            serviceUrl =
                initialPrefs[Preferences.KEY_SERVICE_URL] ?: Preferences.DEFAULT_SERVICE_URL,
            deviceKey = initialPrefs[Preferences.KEY_DEVICE_KEY].orEmpty(),
            deviceSecret = initialSecret,
            isCredentialBlocked = initialPrefs[Preferences.KEY_SUBMISSIONS_BLOCKED] == true,
            consecutiveAuthFailures = initialPrefs[Preferences.KEY_CONSECUTIVE_AUTH_FAILURES] ?: 0,
            isLoading = false,
        )
      }

      // Persist the default service URL on first run.
      if (!initialPrefs.contains(Preferences.KEY_SERVICE_URL)) {
        settingsDataStore.edit { it[Preferences.KEY_SERVICE_URL] = Preferences.DEFAULT_SERVICE_URL }
      }

      startRuntimeStateObserver()
      startSaveCollectors()
      startFailedReportCountCollector()
    }
  }

  private fun startRuntimeStateObserver() {
    viewModelScope.launch {
      credentialStatusFlow.collect { status ->
        _uiState.update {
          it.copy(
              isCredentialBlocked = status.isBlocked,
              consecutiveAuthFailures = status.consecutiveAuthFailures,
          )
        }
      }
    }
  }

  private fun startSaveCollectors() {
    viewModelScope.launch {
      _uiState
          .map { it.serviceUrl }
          .distinctUntilChanged()
          .drop(1)
          .debounce(Config.Ui.SAVE_DEBOUNCE_MS)
          .collect { url ->
            settingsDataStore.edit { it[Preferences.KEY_SERVICE_URL] = url }
            _savedEvents.tryEmit(Unit)
          }
    }

    viewModelScope.launch {
      _uiState
          .map { it.deviceKey }
          .distinctUntilChanged()
          .drop(1)
          .debounce(Config.Ui.SAVE_DEBOUNCE_MS)
          .collect { key ->
            settingsDataStore.edit { it[Preferences.KEY_DEVICE_KEY] = key }
            _savedEvents.tryEmit(Unit)
          }
    }

    viewModelScope.launch {
      _uiState
          .map { it.deviceSecret }
          .distinctUntilChanged()
          .drop(1)
          .debounce(Config.Ui.SAVE_DEBOUNCE_MS)
          .collect { secret ->
            encryptedSecretStore.set(secret)
            _savedEvents.tryEmit(Unit)
          }
    }
  }

  private fun startFailedReportCountCollector() {
    viewModelScope.launch {
      locationReportDao.getPermanentlyFailedReportCount().collect { count ->
        _uiState.update { it.copy(failedReportCount = count) }
      }
    }
  }

  // Settings update functions - these only update in-memory state
  // The debounced collectors in init will handle saving to DataStore
  fun updateServiceUrl(url: String) = _uiState.update { it.copy(serviceUrl = url) }

  fun updateDeviceKey(key: String) = _uiState.update { it.copy(deviceKey = key) }

  fun updateDeviceSecret(secret: String) = _uiState.update { it.copy(deviceSecret = secret) }

  // Dialog management
  fun showResetDialog() = _uiState.update { it.copy(showResetDialog = true) }

  fun dismissResetDialog() = _uiState.update { it.copy(showResetDialog = false) }

  fun showRetryDialog() = _uiState.update { it.copy(showRetryDialog = true) }

  fun dismissRetryDialog() = _uiState.update { it.copy(showRetryDialog = false) }

  fun showExportDialog() = _uiState.update { it.copy(showExportDialog = true) }

  fun dismissExportDialog() = _uiState.update { it.copy(showExportDialog = false) }

  fun showDeleteDialog() = _uiState.update { it.copy(showDeleteDialog = true) }

  fun dismissDeleteDialog() = _uiState.update { it.copy(showDeleteDialog = false) }

  fun clearToastMessage() = _uiState.update { it.copy(toastMessage = null) }

  // Action functions
  fun resetCredentialBlock() {
    viewModelScope.launch {
      val result = settingsRepository.resetCredentialBlock()

      _uiState.update {
        it.copy(
            showResetDialog = false,
            toastMessage =
                ToastMessage.Simple(
                    if (result.isSuccess) R.string.toast_credential_reset_success
                    else R.string.toast_credential_reset_failure
                ),
        )
      }
    }
  }

  fun retryFailedReports() {
    viewModelScope.launch {
      val count = _uiState.value.failedReportCount
      val result = settingsRepository.retryFailedReports()

      _uiState.update {
        it.copy(
            showRetryDialog = false,
            toastMessage =
                if (result.isSuccess)
                    ToastMessage.Plural(R.plurals.toast_reports_queued_for_retry, count)
                else ToastMessage.Simple(R.string.toast_retry_failure),
        )
      }
    }
  }

  fun exportFailedReports() {
    viewModelScope.launch {
      val result = settingsRepository.exportFailedReports()

      _uiState.update {
        it.copy(
            showExportDialog = false,
            toastMessage =
                ToastMessage.Simple(
                    if (result.isSuccess) R.string.toast_export_success
                    else R.string.toast_export_failure
                ),
        )
      }
    }
  }

  fun deleteFailedReports() {
    viewModelScope.launch {
      val result = settingsRepository.deleteFailedReports()

      _uiState.update {
        it.copy(
            showDeleteDialog = false,
            toastMessage =
                ToastMessage.Simple(
                    if (result.isSuccess) R.string.toast_delete_success
                    else R.string.toast_delete_failure
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
  private val settingsDataStore = context.settingsDataStore

  suspend fun resetCredentialBlock(): Result<Unit> =
      runCatching {
            settingsDataStore.edit {
              it[Preferences.KEY_SUBMISSIONS_BLOCKED] = false
              it[Preferences.KEY_CONSECUTIVE_AUTH_FAILURES] = 0
            }

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SubmissionWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    SubmissionWorker.buildWorkRequest(),
                )

            // Cancel the notification if it's showing
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                Notifications.Ids.CREDENTIAL
            )
          }
          .onFailure { Log.w(TAG, "Failed to reset credential block", it) }

  suspend fun retryFailedReports(): Result<Unit> =
      runCatching<Unit> {
            locationReportDao.retryPermanentlyFailedReports()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SubmissionWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    SubmissionWorker.buildWorkRequest(expedited = true),
                )
          }
          .onFailure { Log.w(TAG, "Failed to retry failed reports", it) }

  suspend fun exportFailedReports(): Result<Unit> = SubmissionWorker.exportFailedReports(context)

  suspend fun deleteFailedReports(): Result<Unit> =
      runCatching { locationReportDao.deletePermanentlyFailedReports() }
          .onFailure { Log.w(TAG, "Failed to delete failed reports", it) }

  private companion object {
    const val TAG = "SettingsRepository"
  }
}
