package com.calindora.follow.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calindora.follow.Config
import com.calindora.follow.DistanceUnit
import com.calindora.follow.R
import com.calindora.follow.SettingsViewModel
import com.calindora.follow.SpeedUnit
import com.calindora.follow.UrlValidationError
import com.calindora.follow.ui.components.ActionButtonWithDescription
import com.calindora.follow.ui.components.ConfirmationDialog
import com.calindora.follow.ui.components.SettingsDropdownItem
import com.calindora.follow.ui.components.SettingsTextFieldItem
import com.calindora.follow.ui.theme.Spacing
import com.calindora.follow.validateServiceUrl
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val scrollState = rememberScrollState()
  val snackbarHostState = remember { SnackbarHostState() }

  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Focus chain: URL -> Key -> Secret
  val urlFocus = remember { FocusRequester() }
  val keyFocus = remember { FocusRequester() }
  val secretFocus = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current

  // Briefly flash a "Saved" indicator when a debounced save commits
  var savedIndicatorVisible by remember { mutableStateOf(false) }
  LaunchedEffect(viewModel) {
    viewModel.savedEvents.collect {
      savedIndicatorVisible = true
      delay(Config.Ui.SAVED_INDICATOR_VISIBLE_MS)
      savedIndicatorVisible = false
    }
  }

  // Display snackbar messages for one-shot operations
  LaunchedEffect(snackbarHostState, viewModel) {
    viewModel.snackbarEvents.collect { message ->
      snackbarHostState.showSnackbar(
          message = message.resolve(context),
          duration = SnackbarDuration.Short,
      )
    }
  }

  // Resolve URL validation error strings up front so they can be used in the validate lambda
  // (which runs during composition but isn't itself @Composable). Keep these in lockstep with the
  // UrlValidationError sealed class.
  val urlEmptyError = stringResource(UrlValidationError.Empty.errorRes)
  val urlMalformedError = stringResource(UrlValidationError.Malformed.errorRes)
  val urlNotHttpsError = stringResource(UrlValidationError.NotHttps.errorRes)
  val urlNoHostError = stringResource(UrlValidationError.NoHost.errorRes)

  Scaffold(
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.action_settings)) },
            navigationIcon = {
              IconButton(onClick = { activity?.finish() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.action_back),
                )
              }
            },
            actions = { SavedIndicator(visible = savedIndicatorVisible) },
        )
      },
  ) { paddingValues ->
    Column(
        modifier =
            Modifier.padding(paddingValues)
                .padding(Spacing.lg)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
    ) {
      // Connection settings
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
          Text(
              text = stringResource(R.string.preference_category_connection),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
          )

          Spacer(modifier = Modifier.height(Spacing.md))

          SettingsTextFieldItem(
              label = stringResource(R.string.preference_url),
              value = uiState.serviceUrl,
              onValueChange = viewModel::updateServiceUrl,
              focusRequester = urlFocus,
              keyboardOptions =
                  KeyboardOptions(
                      keyboardType = KeyboardType.Uri,
                      imeAction = ImeAction.Next,
                      autoCorrectEnabled = false,
                  ),
              keyboardActions = KeyboardActions(onNext = { keyFocus.requestFocus() }),
              validate = { value ->
                when (validateServiceUrl(value)) {
                  UrlValidationError.Empty -> urlEmptyError
                  UrlValidationError.Malformed -> urlMalformedError
                  UrlValidationError.NotHttps -> urlNotHttpsError
                  UrlValidationError.NoHost -> urlNoHostError
                  null -> null
                }
              },
          )

          Spacer(modifier = Modifier.height(Spacing.md))

          SettingsTextFieldItem(
              label = stringResource(R.string.preference_device_key),
              value = uiState.deviceKey,
              onValueChange = viewModel::updateDeviceKey,
              focusRequester = keyFocus,
              keyboardOptions =
                  KeyboardOptions(
                      keyboardType = KeyboardType.Ascii,
                      imeAction = ImeAction.Next,
                      autoCorrectEnabled = false,
                  ),
              keyboardActions = KeyboardActions(onNext = { secretFocus.requestFocus() }),
          )

          Spacer(modifier = Modifier.height(Spacing.md))

          SettingsTextFieldItem(
              label = stringResource(R.string.preference_device_secret),
              value = uiState.deviceSecret,
              onValueChange = viewModel::updateDeviceSecret,
              focusRequester = secretFocus,
              isPassword = true,
              keyboardOptions =
                  KeyboardOptions(
                      keyboardType = KeyboardType.Password,
                      imeAction = ImeAction.Done,
                      autoCorrectEnabled = false,
                  ),
              keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
          )
        }
      }

      Spacer(modifier = Modifier.height(Spacing.xl))

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
          Text(
              text = stringResource(R.string.preference_category_display),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
          )

          Spacer(modifier = Modifier.height(Spacing.md))

          SettingsDropdownItem(
              label = stringResource(R.string.preference_distance_unit),
              selected = uiState.distanceUnit,
              options = DistanceUnit.entries,
              optionLabel = { stringResource(it.labelRes) },
              onSelected = viewModel::updateDistanceUnit,
          )

          Spacer(modifier = Modifier.height(Spacing.md))

          SettingsDropdownItem(
              label = stringResource(R.string.preference_speed_unit),
              selected = uiState.speedUnit,
              options = SpeedUnit.entries,
              optionLabel = { stringResource(it.labelRes) },
              onSelected = viewModel::updateSpeedUnit,
          )
        }
      }

      Spacer(modifier = Modifier.height(Spacing.xl))

      // Status section
      Text(
          text = stringResource(R.string.preference_category_status),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
      )

      Spacer(modifier = Modifier.height(Spacing.sm))

      CredentialStatusCard(
          isBlocked = uiState.isCredentialBlocked,
          consecutiveAuthFailures = uiState.consecutiveAuthFailures,
      )

      Spacer(modifier = Modifier.height(Spacing.lg))

      // Reset Credential Block (visible if blocked or enough auth failures)
      if (uiState.shouldShowResetButton) {
        ActionButtonWithDescription(
            text = stringResource(R.string.preference_reset_credential_block),
            description = stringResource(R.string.preference_reset_credential_block_summary),
            onClick = { viewModel.showResetDialog() },
        )
      }

      // Failed report management
      if (uiState.failedReportCount > 0) {
        Spacer(modifier = Modifier.height(Spacing.lg))

        FailedReportsActions(
            failedReportCount = uiState.failedReportCount,
            onRetryClick = { viewModel.showRetryDialog() },
            onExportClick = { viewModel.showExportDialog() },
            onDeleteClick = { viewModel.showDeleteDialog() },
        )
      }
    }
  }

  // Reset Confirmation Dialog
  if (uiState.showResetDialog) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_reset_title),
        text = stringResource(R.string.dialog_reset_message),
        confirmText = stringResource(R.string.action_reset),
        onConfirm = { viewModel.resetCredentialBlock() },
        onDismiss = { viewModel.dismissResetDialog() },
    )
  }

  // Retry Failed Reports Confirmation Dialog
  if (uiState.showRetryDialog) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_retry_title),
        text =
            pluralStringResource(
                R.plurals.dialog_retry_message,
                uiState.failedReportCount,
                uiState.failedReportCount,
            ),
        confirmText = stringResource(R.string.action_retry),
        onConfirm = { viewModel.retryFailedReports() },
        onDismiss = { viewModel.dismissRetryDialog() },
    )
  }

  // Export Failed Reports Dialog
  if (uiState.showExportDialog) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_export_title),
        text =
            pluralStringResource(
                R.plurals.dialog_export_message,
                uiState.failedReportCount,
                uiState.failedReportCount,
            ),
        confirmText = stringResource(R.string.action_export),
        onConfirm = { viewModel.exportFailedReports() },
        onDismiss = { viewModel.dismissExportDialog() },
    )
  }

  // Delete Failed Reports Dialog
  if (uiState.showDeleteDialog) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_delete_title),
        text =
            pluralStringResource(
                R.plurals.dialog_delete_message,
                uiState.failedReportCount,
                uiState.failedReportCount,
            ),
        confirmText = stringResource(R.string.action_delete),
        onConfirm = { viewModel.deleteFailedReports() },
        onDismiss = { viewModel.dismissDeleteDialog() },
    )
  }
}
