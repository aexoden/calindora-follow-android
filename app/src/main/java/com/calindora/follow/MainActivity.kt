package com.calindora.follow

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import com.calindora.follow.ui.theme.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private val DISPLAY_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

/** A snackbar to show on the main screen, optionally with one action. */
data class SnackbarRequest(val message: UiText, val action: Action? = null) {
  enum class Action {
    OPEN_APP_SETTINGS
  }
}

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
                  message = UiText.Simple(R.string.toast_notifications_denied),
                  action = SnackbarRequest.Action.OPEN_APP_SETTINGS,
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
              SnackbarRequest(UiText.Simple(R.string.toast_location_permission_required))
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
            queueSize =
                locationViewModel.queueSize.collectAsStateWithLifecycle(initialValue = 0).value,
            lastSubmissionTime =
                locationViewModel.lastSubmissionTime
                    .collectAsStateWithLifecycle(initialValue = 0L)
                    .value,
            syncWorkInfo = syncWorkInfo,
            onServiceToggle = { isChecked -> onButtonService(isChecked) },
            onTrackToggle = { isChecked -> onButtonTrack(isChecked) },
            onLogToggle = { isChecked -> onButtonLog(isChecked) },
            onClearClick = { locationViewModel.clearQueue() },
            onDropFirstClick = { locationViewModel.dropOldestUnsubmittedReport() },
            onForceSyncClick = { locationViewModel.forceSubmission() },
            onSettingsClick = {
              val intent = Intent(this, SettingsActivity::class.java)
              startActivity(intent)
            },
            isBound = isBound,
            isTracking = serviceState?.tracking == true,
            isLogging = serviceState?.logging == true,
            locationData = serviceState?.location,
            credentialStatus = credentialStatus,
            displayPreferences = displayPreferences,
            showLocationSettingsDialog = showLocationSettingsDialog,
            onDismissLocationSettingsDialog = { showLocationSettingsDialog = false },
            snackbarRequests = snackbarRequests,
            onOpenAppSettings = {
              showLocationSettingsDialog = false
              openAppSettings()
            },
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
      _snackbarRequests.tryEmit(SnackbarRequest(UiText.Simple(R.string.toast_logging_start_failed)))
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    queueSize: Int,
    lastSubmissionTime: Long,
    syncWorkInfo: WorkInfo?,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
    onClearClick: () -> Unit,
    onDropFirstClick: () -> Unit,
    onForceSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    locationData: Location?,
    credentialStatus: CredentialStatus,
    displayPreferences: DisplayPreferences,
    showLocationSettingsDialog: Boolean,
    onDismissLocationSettingsDialog: () -> Unit,
    snackbarRequests: SharedFlow<SnackbarRequest>,
    onOpenAppSettings: () -> Unit,
) {
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val openSettingsText = stringResource(R.string.action_open_settings)

  LaunchedEffect(snackbarRequests) {
    snackbarRequests.collect { req ->
      val actionLabel =
          when (req.action) {
            SnackbarRequest.Action.OPEN_APP_SETTINGS -> openSettingsText
            null -> null
          }
      val result =
          snackbarHostState.showSnackbar(
              message = req.message.resolve(context),
              actionLabel = actionLabel,
              duration = SnackbarDuration.Long,
          )
      if (result == SnackbarResult.ActionPerformed) {
        when (req.action) {
          SnackbarRequest.Action.OPEN_APP_SETTINGS -> onOpenAppSettings()
          null -> {}
        }
      }
    }
  }

  var isDebugEnabled by remember { mutableStateOf(false) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
              IconButton(onClick = onSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.settings_24px),
                    contentDescription = stringResource(R.string.action_settings),
                )
              }
            },
        )
      }
  ) { paddingValues ->
    Column(
        modifier =
            Modifier.padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
    ) {
      LocationStatusSection(
          locationData = locationData,
          lastSubmissionTime = lastSubmissionTime,
          queueSize = queueSize,
          syncWorkInfo = if (isDebugEnabled) null else syncWorkInfo,
          displayPreferences = displayPreferences,
      )

      Spacer(modifier = Modifier.height(8.dp))

      CredentialWarningBanner(status = credentialStatus)

      Spacer(modifier = Modifier.height(8.dp))

      ServiceControlsSection(
          isBound = isBound,
          isTracking = isTracking,
          isLogging = isLogging,
          onServiceToggle = onServiceToggle,
          onTrackToggle = onTrackToggle,
          onLogToggle = onLogToggle,
      )

      Spacer(modifier = Modifier.height(24.dp))

      DebugSection(
          isDebugEnabled = isDebugEnabled,
          onDebugToggle = { isDebugEnabled = it },
          syncWorkInfo = syncWorkInfo,
          queueSize = queueSize,
          onClearClick = onClearClick,
          onDropFirstClick = onDropFirstClick,
          onForceSyncClick = onForceSyncClick,
      )
    }
  }

  if (showLocationSettingsDialog) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_location_permission_title),
        text = stringResource(R.string.dialog_location_permission_message),
        confirmText = stringResource(R.string.action_open_settings),
        onConfirm = onOpenAppSettings,
        onDismiss = onDismissLocationSettingsDialog,
    )
  }
}

@Composable
fun LocationStatusSection(
    locationData: Location?,
    lastSubmissionTime: Long,
    queueSize: Int,
    syncWorkInfo: WorkInfo?,
    displayPreferences: DisplayPreferences,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Column(modifier = Modifier.padding(16.dp)) {
      if (locationData != null) {
        val locale = LocalLocale.current.platformLocale
        val distanceAbbr = stringResource(displayPreferences.distanceUnit.abbreviationRes)
        val speedAbbr = stringResource(displayPreferences.speedUnit.abbreviationRes)

        StatusRow(
            label = stringResource(R.string.label_gps_time),
            value = DISPLAY_FORMATTER.format(Instant.ofEpochMilli(locationData.time)),
        )
        StatusRow(
            label = stringResource(R.string.label_latitude),
            value = String.format(locale, "%.5f°", locationData.latitude),
        )
        StatusRow(
            label = stringResource(R.string.label_longitude),
            value = String.format(locale, "%.5f°", locationData.longitude),
        )
        StatusRow(
            label = stringResource(R.string.label_altitude),
            value =
                String.format(
                    locale,
                    "%.2f %s",
                    displayPreferences.distanceUnit.fromMeters(locationData.altitude),
                    distanceAbbr,
                ),
        )
        StatusRow(
            label = stringResource(R.string.label_speed),
            value =
                String.format(
                    locale,
                    "%.2f %s",
                    displayPreferences.speedUnit.fromMetersPerSecond(locationData.speed.toDouble()),
                    speedAbbr,
                ),
        )
        StatusRow(
            label = stringResource(R.string.label_bearing),
            value = String.format(locale, "%.2f°", locationData.bearing),
        )
        StatusRow(
            label = stringResource(R.string.label_accuracy),
            value =
                String.format(
                    locale,
                    "%.2f %s",
                    displayPreferences.distanceUnit.fromMeters(locationData.accuracy.toDouble()),
                    distanceAbbr,
                ),
        )
      } else {
        Text(stringResource(R.string.label_waiting_for_location))
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      StatusRow(
          label = stringResource(R.string.label_submission_time),
          value = formatSubmissionTime(lastSubmissionTime),
      )
      StatusRow(
          label = stringResource(R.string.label_submission_queue_size),
          value = queueSize.toString(),
      )
      NextSyncStatusRow(workInfo = syncWorkInfo)
    }
  }
}

@Composable
fun StatusRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(0.4f),
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(0.6f),
    )
  }
}

@Composable
fun CredentialWarningBanner(status: CredentialStatus) {
  AnimatedVisibility(
      visible = status.isBlocked || status.consecutiveAuthFailures > 0,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
  ) {
    val containerColor =
        if (status.isBlocked) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor =
        if (status.isBlocked) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onTertiaryContainer
    val text =
        if (status.isBlocked) {
          stringResource(R.string.credential_warning)
        } else {
          stringResource(
              R.string.credential_warning_failures,
              status.consecutiveAuthFailures,
              Config.Submission.MAX_AUTH_FAILURES,
          )
        }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
      Text(
          text = text,
          color = contentColor,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(8.dp),
      )
    }
  }
}

@Composable
fun ServiceControlsSection(
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
) {
  Column {
    ToggleButton(
        checked = isBound,
        onCheckedChange = onServiceToggle,
        enabledText = stringResource(R.string.label_on),
        disabledText = stringResource(R.string.label_off),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
      ToggleButton(
          checked = isLogging,
          onCheckedChange = onLogToggle,
          enabledText = stringResource(R.string.label_logging_on),
          disabledText = stringResource(R.string.label_logging_off),
          enabled = isBound,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
      )

      ToggleButton(
          checked = isTracking,
          onCheckedChange = onTrackToggle,
          enabledText = stringResource(R.string.label_tracking_on),
          disabledText = stringResource(R.string.label_tracking_off),
          enabled = isBound,
          modifier = Modifier.weight(1f).padding(start = 8.dp),
      )
    }
  }
}

@Composable
private fun DebugSection(
    isDebugEnabled: Boolean,
    onDebugToggle: (Boolean) -> Unit,
    syncWorkInfo: WorkInfo?,
    queueSize: Int,
    onClearClick: () -> Unit,
    onDropFirstClick: () -> Unit,
    onForceSyncClick: () -> Unit,
) {
  var showClearConfirm by remember { mutableStateOf(false) }
  var showDropFirstConfirm by remember { mutableStateOf(false) }

  Column {
    ToggleButton(
        checked = isDebugEnabled,
        onCheckedChange = onDebugToggle,
        enabledText = stringResource(R.string.label_debug_on),
        disabledText = stringResource(R.string.label_debug_off),
        modifier = Modifier.fillMaxWidth(),
    )

    AnimatedVisibility(
        visible = isDebugEnabled,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
      Column {
        Spacer(modifier = Modifier.height(16.dp))

        SyncStatusCard(workInfo = syncWorkInfo)

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
          Button(
              onClick = { showClearConfirm = true },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = MaterialTheme.colorScheme.error,
                      contentColor = MaterialTheme.colorScheme.onError,
                  ),
              modifier = Modifier.weight(1f).padding(end = 8.dp),
          ) {
            Text(stringResource(R.string.label_clear_queue))
          }

          Button(
              onClick = { showDropFirstConfirm = true },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = MaterialTheme.colorScheme.error,
                      contentColor = MaterialTheme.colorScheme.onError,
                  ),
              modifier = Modifier.weight(1f).padding(start = 8.dp),
          ) {
            Text(stringResource(R.string.label_drop_first_queued))
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onForceSyncClick, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.label_force_sync))
        }
      }
    }
  }

  if (showClearConfirm) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_clear_queue_title),
        text = pluralStringResource(R.plurals.dialog_clear_queue_message, queueSize, queueSize),
        confirmText = stringResource(R.string.action_clear),
        onConfirm = {
          showClearConfirm = !showClearConfirm
          onClearClick()
        },
        onDismiss = { showClearConfirm = !showClearConfirm },
    )
  }

  if (showDropFirstConfirm) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_drop_first_title),
        text = stringResource(R.string.dialog_drop_first_message),
        confirmText = stringResource(R.string.action_drop),
        onConfirm = {
          showDropFirstConfirm = !showDropFirstConfirm
          onDropFirstClick()
        },
        onDismiss = { showDropFirstConfirm = !showDropFirstConfirm },
    )
  }
}

@Composable
fun ToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabledText: String,
    disabledText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Button(
      onClick = { onCheckedChange(!checked) },
      modifier = modifier,
      enabled = enabled,
      colors =
          ButtonDefaults.buttonColors(
              containerColor =
                  if (checked) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant,
              contentColor =
                  if (checked) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          ),
  ) {
    Text(if (checked) enabledText else disabledText)
  }
}

@Composable
private fun NextSyncStatusRow(workInfo: WorkInfo?) {
  if (workInfo == null || workInfo.state != WorkInfo.State.ENQUEUED) return

  val target = workInfo.nextScheduleTimeMillis
  if (target == Long.MAX_VALUE) return
  val remaining = rememberCountdown(target)

  if (remaining > 1500L) {
    StatusRow(
        label = stringResource(R.string.label_next_sync),
        value = formatCountdown(remaining),
    )
  }
}

@Composable
private fun SyncStatusCard(workInfo: WorkInfo?) {
  Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          text = stringResource(R.string.sync_status_card_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      if (workInfo == null) {
        Text(
            text = stringResource(R.string.sync_status_no_work),
            style = MaterialTheme.typography.bodyMedium,
        )
        return@Card
      }

      StatusRow(label = stringResource(R.string.label_sync_state), value = workInfo.state.toLabel())
      StatusRow(
          label = stringResource(R.string.label_sync_attempts),
          value = workInfo.runAttemptCount.toString(),
      )

      if (workInfo.state == WorkInfo.State.ENQUEUED) {
        val target = workInfo.nextScheduleTimeMillis
        if (target != Long.MAX_VALUE) {
          val remaining = rememberCountdown(target)
          StatusRow(
              label = stringResource(R.string.label_next_sync),
              value = if (remaining > 0) formatCountdown(remaining) else "-",
          )
        }
      }

      if (workInfo.state == WorkInfo.State.FAILED) {
        val errorReason = workInfo.outputData.getString(SubmissionWorker.OUTPUT_KEY_ERROR_REASON)
        if (!errorReason.isNullOrEmpty()) {
          StatusRow(label = stringResource(R.string.label_sync_last_error), value = errorReason)
        }
      }

      val stopReason = workInfo.stopReason
      if (stopReason != WorkInfo.STOP_REASON_NOT_STOPPED) {
        StatusRow(
            label = stringResource(R.string.label_sync_stop_reason),
            value = stopReasonLabel(stopReason),
        )
      }
    }
  }
}

@Composable
private fun stopReasonLabel(stopReason: Int): String =
    when (stopReason) {
      WorkInfo.STOP_REASON_CANCELLED_BY_APP -> stringResource(R.string.stop_reason_cancelled_by_app)
      WorkInfo.STOP_REASON_PREEMPT -> stringResource(R.string.stop_reason_preempt)
      WorkInfo.STOP_REASON_TIMEOUT -> stringResource(R.string.stop_reason_timeout)
      WorkInfo.STOP_REASON_DEVICE_STATE -> stringResource(R.string.stop_reason_device_state)
      WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW ->
          stringResource(R.string.stop_reason_battery_not_low)
      WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> stringResource(R.string.stop_reason_charging)
      WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY ->
          stringResource(R.string.stop_reason_connectivity)
      WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE ->
          stringResource(R.string.stop_reason_device_idle)
      WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW ->
          stringResource(R.string.stop_reason_storage_not_low)
      WorkInfo.STOP_REASON_QUOTA -> stringResource(R.string.stop_reason_quota)
      WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION ->
          stringResource(R.string.stop_reason_background_restriction)
      WorkInfo.STOP_REASON_APP_STANDBY -> stringResource(R.string.stop_reason_app_standby)
      WorkInfo.STOP_REASON_USER -> stringResource(R.string.stop_reason_user)
      WorkInfo.STOP_REASON_SYSTEM_PROCESSING ->
          stringResource(R.string.stop_reason_system_processing)
      WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED ->
          stringResource(R.string.stop_reason_estimated_app_launch_time_changed)
      WorkInfo.STOP_REASON_UNKNOWN -> stringResource(R.string.stop_reason_unknown)
      else -> stringResource(R.string.stop_reason_other, stopReason)
    }

@Composable
private fun WorkInfo.State.toLabel(): String =
    stringResource(
        when (this) {
          WorkInfo.State.ENQUEUED -> R.string.sync_state_enqueued
          WorkInfo.State.RUNNING -> R.string.sync_state_running
          WorkInfo.State.SUCCEEDED -> R.string.sync_state_succeeded
          WorkInfo.State.FAILED -> R.string.sync_state_failed
          WorkInfo.State.BLOCKED -> R.string.sync_state_blocked
          WorkInfo.State.CANCELLED -> R.string.sync_state_cancelled
        }
    )

@Composable
private fun rememberCountdown(targetTime: Long): Long {
  var now by remember(targetTime) { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(targetTime) {
    while (System.currentTimeMillis() < targetTime) {
      now = System.currentTimeMillis()
      delay(500L)
    }
    now = System.currentTimeMillis()
  }

  return (targetTime - now).coerceAtLeast(0L)
}

private fun formatCountdown(remainingMs: Long): String {
  val totalSeconds = (remainingMs + 999L) / 1000L
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

@Composable
private fun formatSubmissionTime(timestamp: Long): String {
  return if (timestamp > 0) {
    DISPLAY_FORMATTER.format(Instant.ofEpochMilli(timestamp))
  } else {
    stringResource(R.string.label_never)
  }
}
