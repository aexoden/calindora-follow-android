package com.calindora.follow

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.calindora.follow.ui.main.MainScreen
import com.calindora.follow.ui.main.MainScreenCallbacks
import com.calindora.follow.ui.main.MainScreenState
import com.calindora.follow.ui.main.SnackbarRequest
import com.calindora.follow.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private var binder: FollowService.FollowBinder? = null
  private var stateCollectionJob: Job? = null

  private val _snackbarRequests = MutableSharedFlow<SnackbarRequest>(extraBufferCapacity = 1)
  private val snackbarRequests: SharedFlow<SnackbarRequest> = _snackbarRequests.asSharedFlow()

  private val locationViewModel: LocationViewModel by viewModels {
    LocationViewModel.factory(appContainer)
  }

  /** Latest snapshot from the bound service, or null if we aren't bound. */
  private val serviceStateFlow = MutableStateFlow<FollowService.ServiceState?>(null)

  /** Shown when the user permanently denied location permission. Lost on recreation. */
  private var showLocationSettingsDialog by mutableStateOf(false)

  private val connection =
      object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
          val followBinder = service as FollowService.FollowBinder
          binder = followBinder

          stateCollectionJob = lifecycleScope.launch {
            followBinder.getService().state.collect { serviceStateFlow.value = it }
          }
        }

        override fun onServiceDisconnected(name: ComponentName) {
          binder = null
          stateCollectionJob?.cancel()
          stateCollectionJob = null
          serviceStateFlow.value = null
        }
      }

  private val requestNotificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
          _snackbarRequests.tryEmit(
              SnackbarRequest(
                  message = UiText.Simple(R.string.message_notifications_denied),
                  action = SnackbarRequest.Action.OPEN_APP_NOTIFICATION_SETTINGS,
              )
          )
        }
      }

  private val requestLocationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          startService()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
          _snackbarRequests.tryEmit(
              SnackbarRequest(UiText.Simple(R.string.message_location_permission_required))
          )
        } else {
          showLocationSettingsDialog = true
        }
      }

  /*
   * Activity Methods
   */

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    checkNotificationPermission()

    val credentialFlow = credentialStatusFlow
    val displayFlow = displayPreferencesFlow

    setContent {
      AppTheme {
        val serviceState by serviceStateFlow.collectAsStateWithLifecycle()
        val credentialStatus by
            credentialFlow.collectAsStateWithLifecycle(initialValue = CredentialStatus.INITIAL)
        val displayPreferences by
            displayFlow.collectAsStateWithLifecycle(initialValue = DisplayPreferences.DEFAULT)
        val syncWorkInfo by
            locationViewModel.syncWorkInfo.collectAsStateWithLifecycle(initialValue = null)
        val isBound = serviceState != null

        MainScreen(
            state =
                MainScreenState(
                    queueSize =
                        locationViewModel.queueSize
                            .collectAsStateWithLifecycle(initialValue = 0)
                            .value,
                    lastSubmissionTime =
                        locationViewModel.lastSubmissionTime
                            .collectAsStateWithLifecycle(initialValue = 0L)
                            .value,
                    syncWorkInfo = syncWorkInfo,
                    isBound = isBound,
                    isTracking = serviceState?.tracking == true,
                    isLogging = serviceState?.logging == true,
                    locationData = serviceState?.location,
                    credentialStatus = credentialStatus,
                    displayPreferences = displayPreferences,
                    showLocationSettingsDialog = showLocationSettingsDialog,
                ),
            callbacks =
                MainScreenCallbacks(
                    onServiceToggle = ::onButtonService,
                    onTrackToggle = ::onButtonTrack,
                    onLogToggle = ::onButtonLog,
                    onClearClick = locationViewModel::clearQueue,
                    onDropFirstClick = locationViewModel::dropOldestUnsubmittedReport,
                    onForceSyncClick = locationViewModel::forceSubmission,
                    onSettingsClick = {
                      val intent = Intent(this, SettingsActivity::class.java)
                      startActivity(intent)
                    },
                    onDismissLocationSettingsDialog = { showLocationSettingsDialog = false },
                    onOpenAppSettings = {
                      showLocationSettingsDialog = false
                      openAppSettings()
                    },
                    onOpenAppNotificationSettings = ::openAppNotificationSettings,
                ),
            snackbarRequests = snackbarRequests,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()

    if (
        showLocationSettingsDialog &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    ) {
      showLocationSettingsDialog = false
    }
  }

  override fun onStart() {
    super.onStart()
    bindService()
  }

  override fun onStop() {
    super.onStop()
    unbindService()
  }

  /*
   * UI Callback Methods
   */

  private fun onButtonService(isChecked: Boolean) {
    if (isChecked) {
      if (
          ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
              PackageManager.PERMISSION_GRANTED
      ) {
        startService()
      } else {
        requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    } else {
      binder?.getService()?.let { service ->
        service.setLogging(false)
        service.tracking = false
      }
      stopService()
    }
  }

  private fun onButtonLog(isChecked: Boolean) {
    val service = binder?.getService() ?: return
    val success = service.setLogging(isChecked)

    if (!success) {
      _snackbarRequests.tryEmit(
          SnackbarRequest(UiText.Simple(R.string.message_logging_start_failed))
      )
    }
  }

  private fun onButtonTrack(isChecked: Boolean) {
    binder?.getService()?.let { it.tracking = isChecked }
  }

  private fun checkNotificationPermission() {
    if (
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  /*
   * Public Methods
   */

  /*
   * Private Methods
   */

  private fun bindService() {
    if (serviceStateFlow.value == null) {
      Intent(this, FollowService::class.java).also { intent -> bindService(intent, connection, 0) }
    }
  }

  private fun unbindService() {
    if (serviceStateFlow.value != null) {
      unbindService(connection)
      stateCollectionJob?.cancel()
      stateCollectionJob = null
      binder = null
      serviceStateFlow.value = null
    }
  }

  private fun startService() {
    Intent(this, FollowService::class.java).also { intent -> startForegroundService(intent) }
    bindService()
  }

  private fun stopService() {
    if (serviceStateFlow.value != null) {
      unbindService()
    }

    Intent(this, FollowService::class.java).also { intent -> stopService(intent) }
  }

  private fun openAppSettings() {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.fromParts("package", packageName, null)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    startActivity(intent)
  }

  private fun openAppNotificationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    startActivity(intent)
  }
}
