package com.calindora.follow.ui.main

import android.location.Location
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.calindora.follow.CredentialStatus
import com.calindora.follow.DisplayPreferences
import com.calindora.follow.R
import com.calindora.follow.UiText
import com.calindora.follow.ui.components.ConfirmationDialog
import kotlinx.coroutines.flow.SharedFlow

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
    onOpenAppNotificationSettings: () -> Unit,
) {
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val openSettingsText = stringResource(R.string.action_open_settings)

  LaunchedEffect(snackbarRequests) {
    snackbarRequests.collect { req ->
      val actionLabel =
          when (req.action) {
            SnackbarRequest.Action.OPEN_APP_NOTIFICATION_SETTINGS -> openSettingsText
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
          SnackbarRequest.Action.OPEN_APP_NOTIFICATION_SETTINGS -> onOpenAppNotificationSettings()
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

/** A snackbar to show on the main screen, optionally with one action. */
data class SnackbarRequest(val message: UiText, val action: Action? = null) {
  enum class Action {
    OPEN_APP_NOTIFICATION_SETTINGS
  }
}
