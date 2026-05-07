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
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val FEET_PER_METER = 3.2808399

private val DISPLAY_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

class MainActivity : ComponentActivity() {
  private val locationViewModel: LocationViewModel by viewModels()
  private var binder: FollowService.FollowBinder? = null
  private var stateCollectionJob: Job? = null

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
          Toast.makeText(this, R.string.toast_notifications_denied, Toast.LENGTH_LONG).show()
        }
      }

  private val requestLocationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          startService()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
          Toast.makeText(this, R.string.toast_location_permission_required, Toast.LENGTH_LONG)
              .show()
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

    val credentialWarningFlow =
        settingsDataStore.data
            .map { it[Preferences.KEY_SUBMISSIONS_BLOCKED] == true }
            .distinctUntilChanged()

    setContent {
      CalindoraFollowTheme {
        val serviceState by serviceStateFlow.collectAsStateWithLifecycle()
        val credentialWarningVisible by
            credentialWarningFlow.collectAsStateWithLifecycle(initialValue = false)
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
            credentialWarningVisible = credentialWarningVisible,
            showLocationSettingsDialog = showLocationSettingsDialog,
            onDismissLocationSettingsDialog = { showLocationSettingsDialog = false },
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
      Toast.makeText(this, R.string.toast_logging_start_failed, Toast.LENGTH_LONG).show()
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
    credentialWarningVisible: Boolean,
    showLocationSettingsDialog: Boolean,
    onDismissLocationSettingsDialog: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
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
    Column(modifier = Modifier.padding(paddingValues).padding(16.dp).fillMaxSize()) {
      LocationStatusSection(
          locationData = locationData,
          lastSubmissionTime = lastSubmissionTime,
          queueSize = queueSize,
          syncWorkInfo = if (isDebugEnabled) null else syncWorkInfo,
      )

      if (isDebugEnabled) {
        SyncStatusCard(workInfo = syncWorkInfo)
      }

      Spacer(modifier = Modifier.height(8.dp))

      CredentialWarningBanner(isVisible = credentialWarningVisible)

      Spacer(modifier = Modifier.height(16.dp))

      ControlsSection(
          isBound = isBound,
          isTracking = isTracking,
          isLogging = isLogging,
          isDebugEnabled = isDebugEnabled,
          onServiceToggle = onServiceToggle,
          onTrackToggle = onTrackToggle,
          onLogToggle = onLogToggle,
          onDebugToggle = { isDebugEnabled = it },
          onClearClick = onClearClick,
          onDropFirstClick = onDropFirstClick,
          onForceSyncClick = onForceSyncClick,
      )
    }
  }

  if (showLocationSettingsDialog) {
    AlertDialog(
        onDismissRequest = onDismissLocationSettingsDialog,
        title = { Text(stringResource(R.string.dialog_location_permission_title)) },
        text = { Text(stringResource(R.string.dialog_location_permission_message)) },
        confirmButton = {
          TextButton(onClick = onOpenAppSettings) {
            Text(stringResource(R.string.action_open_settings))
          }
        },
        dismissButton = {
          TextButton(onClick = onDismissLocationSettingsDialog) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }
}

@Composable
fun LocationStatusSection(
    locationData: Location?,
    lastSubmissionTime: Long,
    queueSize: Int,
    syncWorkInfo: WorkInfo?,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Column(modifier = Modifier.padding(16.dp)) {
      if (locationData != null) {
        val locale = Locale.getDefault()

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
            value = String.format(locale, "%.5f ft", locationData.altitude * FEET_PER_METER),
        )
        StatusRow(
            label = stringResource(R.string.label_speed),
            value =
                String.format(
                    locale,
                    "%.2f MPH",
                    locationData.speed * FEET_PER_METER * 60.0 * 60.0 / 5280.0,
                ),
        )
        StatusRow(
            label = stringResource(R.string.label_bearing),
            value = String.format(locale, "%.2f°", locationData.bearing),
        )
        StatusRow(
            label = stringResource(R.string.label_accuracy),
            value = String.format(locale, "%.2f ft", locationData.accuracy * FEET_PER_METER),
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
fun CredentialWarningBanner(isVisible: Boolean) {
  AnimatedVisibility(
      visible = isVisible,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
  ) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
      Text(
          text = stringResource(R.string.credential_warning),
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(8.dp),
      )
    }
  }
}

@Composable
fun ControlsSection(
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    isDebugEnabled: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
    onDebugToggle: (Boolean) -> Unit,
    onClearClick: () -> Unit,
    onDropFirstClick: () -> Unit,
    onForceSyncClick: () -> Unit,
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

    Spacer(modifier = Modifier.weight(1f))

    ToggleButton(
        checked = isDebugEnabled,
        onCheckedChange = onDebugToggle,
        enabledText = stringResource(R.string.label_debug_on),
        disabledText = stringResource(R.string.label_debug_off),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
      Button(
          onClick = onClearClick,
          enabled = isDebugEnabled,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
      ) {
        Text(stringResource(R.string.label_clear_queue))
      }

      Button(
          onClick = onDropFirstClick,
          enabled = isDebugEnabled,
          modifier = Modifier.weight(1f).padding(start = 8.dp),
      ) {
        Text(stringResource(R.string.label_drop_first_queued))
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onForceSyncClick,
        enabled = isDebugEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.label_force_sync))
    }
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
            value = stopReason.toString(),
        )
      }
    }
  }
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
