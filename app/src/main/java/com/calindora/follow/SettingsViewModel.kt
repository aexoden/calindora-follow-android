package com.calindora.follow

import android.app.Application
import android.app.NotificationManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import java.io.IOException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Settings screen */
data class SettingsUiState(
    val serviceUrl: String = "",
    val deviceKey: String = "",
    val deviceSecret: String = "",
    val distanceUnit: DistanceUnit = DistanceUnit.DEFAULT,
    val speedUnit: SpeedUnit = SpeedUnit.DEFAULT,
    val isCredentialBlocked: Boolean = false,
    val consecutiveAuthFailures: Int = 0,
    val failedReportCount: Int = 0,
    val showResetDialog: Boolean = false,
    val showRetryDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val isLoading: Boolean = true,
) {
  /** Whether the "Reset credential block" action should be offered. */
  val shouldShowResetButton: Boolean
    get() = isCredentialBlocked || consecutiveAuthFailures >= Config.Submission.MAX_AUTH_FAILURES
}

/** ViewModel for the Settings screen */
@OptIn(FlowPreview::class)
class SettingsViewModel(
    private val locationReportDao: LocationReportDao,
    private val settingsDataStore: DataStore<Preferences>,
    private val encryptedSecretStore: SecretStore,
    private val credentialStatusFlow: Flow<CredentialStatus>,
    private val settingsRepository: SettingsActions,
) : ViewModel() {
  private val _uiState = MutableStateFlow(SettingsUiState())
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()

  private val _snackbarEvents = Channel<UiText>(Channel.BUFFERED)
  val snackbarEvents: Flow<UiText> = _snackbarEvents.receiveAsFlow()

  init {
    viewModelScope.launch {
      // Migrate the legacy plaintext secret on first launch. Subsequent calls are no-ops.
      encryptedSecretStore.migrateFromLegacyIfNeeded()

      // Load initial state synchronously before starting observers and save collectors. If
      // DataStore is transiently unreadable, fall back to defaults so the screen still opens; the
      // runtime observers below will pick up a valid snapshot once a later read succeeds.
      val initialPrefs: Preferences =
          try {
            settingsDataStore.data.first()
          } catch (e: IOException) {
            Log.w(TAG, "Failed to read settings DataStore; falling back to default", e)
            emptyPreferences()
          }
      val initialSecret =
          try {
            encryptedSecretStore.get().orEmpty()
          } catch (e: IOException) {
            Log.w(TAG, "Failed to read device secret; falling back to empty", e)
            ""
          }

      _uiState.update {
        it.copy(
            serviceUrl =
                initialPrefs[AppPreferences.KEY_SERVICE_URL] ?: AppPreferences.DEFAULT_SERVICE_URL,
            deviceKey = initialPrefs[AppPreferences.KEY_DEVICE_KEY].orEmpty(),
            deviceSecret = initialSecret,
            distanceUnit = DistanceUnit.fromKey(initialPrefs[AppPreferences.KEY_DISTANCE_UNIT]),
            speedUnit = SpeedUnit.fromKey(initialPrefs[AppPreferences.KEY_SPEED_UNIT]),
            isCredentialBlocked = initialPrefs[AppPreferences.KEY_SUBMISSIONS_BLOCKED] == true,
            consecutiveAuthFailures =
                initialPrefs[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] ?: 0,
            isLoading = false,
        )
      }

      // Persist the default service URL on first run.
      if (!initialPrefs.contains(AppPreferences.KEY_SERVICE_URL)) {
        try {
          settingsDataStore.edit {
            it[AppPreferences.KEY_SERVICE_URL] = AppPreferences.DEFAULT_SERVICE_URL
          }
        } catch (e: IOException) {
          Log.w(TAG, "Failed to persist default service URL", e)
        }
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
    observeAndSave(SettingsUiState::serviceUrl) { url ->
      settingsDataStore.edit { it[AppPreferences.KEY_SERVICE_URL] = url }
    }
    observeAndSave(SettingsUiState::deviceKey) { key ->
      settingsDataStore.edit { it[AppPreferences.KEY_DEVICE_KEY] = key }
    }
    observeAndSave(SettingsUiState::deviceSecret) { secret -> encryptedSecretStore.set(secret) }

    // Enum selections are persisted immediately — no debounce window needed.
    observeAndSave(SettingsUiState::distanceUnit, debounceMs = 0L) { unit ->
      settingsDataStore.edit { it[AppPreferences.KEY_DISTANCE_UNIT] = unit.name }
    }
    observeAndSave(SettingsUiState::speedUnit, debounceMs = 0L) { unit ->
      settingsDataStore.edit { it[AppPreferences.KEY_SPEED_UNIT] = unit.name }
    }
  }

  /**
   * Launch a collector that watches one slice of [_uiState], debounces it (deafult
   * [Config.Ui.SAVE_DEBOUNCE_MS]; pass `0L` to disable), persists the value via [save], and emits a
   * "saved" event so the UI can flash its indicator. The initial state is dropped so the load
   * itself doesn't trigger a redundant write.
   */
  private fun <T> observeAndSave(
      selector: (SettingsUiState) -> T,
      debounceMs: Long = Config.Ui.SAVE_DEBOUNCE_MS,
      save: suspend (T) -> Unit,
  ) {
    viewModelScope.launch {
      _uiState.map(selector).distinctUntilChanged().drop(1).debounce(debounceMs).collect { value ->
        save(value)
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

  // Settings update functions - these only update in-memory state. The debounced collectors in init
  // will handle saving to DataStore.
  fun updateServiceUrl(url: String) = _uiState.update { it.copy(serviceUrl = url) }

  fun updateDeviceKey(key: String) = _uiState.update { it.copy(deviceKey = key) }

  fun updateDeviceSecret(secret: String) = _uiState.update { it.copy(deviceSecret = secret) }

  fun updateDistanceUnit(unit: DistanceUnit) = _uiState.update { it.copy(distanceUnit = unit) }

  fun updateSpeedUnit(unit: SpeedUnit) = _uiState.update { it.copy(speedUnit = unit) }

  // Dialog management
  fun showResetDialog() = _uiState.update { it.copy(showResetDialog = true) }

  fun dismissResetDialog() = _uiState.update { it.copy(showResetDialog = false) }

  fun showRetryDialog() = _uiState.update { it.copy(showRetryDialog = true) }

  fun dismissRetryDialog() = _uiState.update { it.copy(showRetryDialog = false) }

  fun showExportDialog() = _uiState.update { it.copy(showExportDialog = true) }

  fun dismissExportDialog() = _uiState.update { it.copy(showExportDialog = false) }

  fun showDeleteDialog() = _uiState.update { it.copy(showDeleteDialog = true) }

  fun dismissDeleteDialog() = _uiState.update { it.copy(showDeleteDialog = false) }

  // Action functions
  fun resetCredentialBlock() {
    viewModelScope.launch {
      val result = settingsRepository.resetCredentialBlock()
      _uiState.update { it.copy(showResetDialog = false) }
      _snackbarEvents.send(
          UiText.Simple(
              if (result.isSuccess) R.string.message_credential_reset_success
              else R.string.message_credential_reset_failure
          ),
      )
    }
  }

  fun retryFailedReports() {
    viewModelScope.launch {
      val count = _uiState.value.failedReportCount
      val result = settingsRepository.retryFailedReports()
      _uiState.update { it.copy(showRetryDialog = false) }
      _snackbarEvents.send(
          if (result.isSuccess) UiText.Plural(R.plurals.message_reports_queued_for_retry, count)
          else UiText.Simple(R.string.message_retry_failure)
      )
    }
  }

  fun exportFailedReports() {
    viewModelScope.launch {
      val result = settingsRepository.exportFailedReports()
      _uiState.update { it.copy(showExportDialog = false) }
      _snackbarEvents.send(
          UiText.Simple(
              if (result.isSuccess) R.string.message_export_success
              else R.string.message_export_failure
          ),
      )
    }
  }

  fun deleteFailedReports() {
    viewModelScope.launch {
      val result = settingsRepository.deleteFailedReports()
      _uiState.update { it.copy(showDeleteDialog = false) }
      _snackbarEvents.send(
          UiText.Simple(
              if (result.isSuccess) R.string.message_delete_success
              else R.string.message_delete_failure
          ),
      )
    }
  }

  companion object {
    private const val TAG = "SettingsViewModel"

    fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        SettingsViewModel(
            locationReportDao = container.locationReportDao,
            settingsDataStore = container.settingsDataStore,
            encryptedSecretStore = container.encryptedSecretStore,
            credentialStatusFlow = container.credentialStatusFlow,
            settingsRepository = container.settingsRepository,
        )
      }
    }
  }
}

/**
 * Settings-screen actions that touch storage, WorkManager, and notifications. Extracted so tests
 * can substitute an in-memory fake without constructing a real [WorkManager].
 */
interface SettingsActions {
  suspend fun resetCredentialBlock(): Result<Unit>

  suspend fun retryFailedReports(): Result<Unit>

  suspend fun exportFailedReports(): Result<Unit>

  suspend fun deleteFailedReports(): Result<Unit>
}

/** Repository to handle settings-related data operations */
class SettingsRepository(
    private val application: Application,
    private val locationReportDao: LocationReportDao,
    private val settingsDataStore: DataStore<Preferences>,
    private val workManager: WorkManager,
    private val notificationManager: NotificationManager,
) : SettingsActions {
  override suspend fun resetCredentialBlock(): Result<Unit> =
      runCatching {
            settingsDataStore.edit {
              it[AppPreferences.KEY_SUBMISSIONS_BLOCKED] = false
              it[AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES] = 0
            }

            workManager.enqueueUniqueWork(
                SubmissionWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                SubmissionWorker.buildWorkRequest(),
            )

            // Cancel the notification if it's showing
            notificationManager.cancel(Notifications.Ids.CREDENTIAL)
          }
          .onFailure { Log.w(TAG, "Failed to reset credential block", it) }

  override suspend fun retryFailedReports(): Result<Unit> =
      runCatching<Unit> {
            locationReportDao.retryPermanentlyFailedReports()

            workManager.enqueueUniqueWork(
                SubmissionWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                SubmissionWorker.buildWorkRequest(expedited = true),
            )
          }
          .onFailure { Log.w(TAG, "Failed to retry failed reports", it) }

  override suspend fun exportFailedReports(): Result<Unit> =
      SubmissionWorker.exportFailedReports(
          locationReportDao = locationReportDao,
          logsDir = application.getExternalFilesDir("logs"),
      )

  override suspend fun deleteFailedReports(): Result<Unit> =
      runCatching { locationReportDao.deletePermanentlyFailedReports() }
          .onFailure { Log.w(TAG, "Failed to delete failed reports", it) }

  private companion object {
    const val TAG = "SettingsRepository"
  }
}
