package com.calindora.follow

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class SettingsActivity : ComponentActivity() {
  private val viewModel: SettingsViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    setContent { CalindoraFollowTheme { SettingsScreen(viewModel) } }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val scrollState = rememberScrollState()

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

  // Display toast messages for one-shot operations
  LaunchedEffect(uiState.toastMessage) {
    uiState.toastMessage?.let {
      Toast.makeText(context, it.resolve(context), Toast.LENGTH_LONG).show()
      viewModel.clearToastMessage()
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
      }
  ) { paddingValues ->
    Column(
        modifier =
            Modifier.padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
    ) {
      // Connection settings
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
              text = stringResource(R.string.preference_category_connection),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
          )

          Spacer(modifier = Modifier.height(12.dp))

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

          Spacer(modifier = Modifier.height(12.dp))

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

          Spacer(modifier = Modifier.height(12.dp))

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

      Spacer(modifier = Modifier.height(24.dp))

      // Status section
      Text(
          text = stringResource(R.string.preference_category_status),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
      )

      Spacer(modifier = Modifier.height(8.dp))

      CredentialStatusCard(
          isBlocked = uiState.isCredentialBlocked,
          consecutiveAuthFailures = uiState.consecutiveAuthFailures,
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Reset Credential Block (visible if blocked or enough auth failures)
      if (
          uiState.isCredentialBlocked ||
              uiState.consecutiveAuthFailures >= Config.Submission.MAX_AUTH_FAILURES
      ) {
        ActionButtonWithDescription(
            text = stringResource(R.string.preference_reset_credential_block),
            description = stringResource(R.string.preference_reset_credential_block_summary),
            onClick = { viewModel.showResetDialog() },
        )
      }

      // Failed report management
      if (uiState.failedReportCount > 0) {
        Spacer(modifier = Modifier.height(16.dp))

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

@Composable
private fun ActionButtonWithDescription(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { Text(text) }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(title) },
      text = { Text(text) },
      confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
      },
  )
}

@Composable
private fun FailedReportsActions(
    failedReportCount: Int,
    onRetryClick: () -> Unit,
    onExportClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
  ActionButtonWithDescription(
      text = stringResource(R.string.action_retry_failed_reports),
      description =
          pluralStringResource(
              R.plurals.failed_reports_retry_summary,
              failedReportCount,
              failedReportCount,
          ),
      onClick = onRetryClick,
  )

  Spacer(modifier = Modifier.height(16.dp))

  ActionButtonWithDescription(
      text = stringResource(R.string.action_export_failed_reports),
      description =
          pluralStringResource(
              R.plurals.failed_reports_export_summary,
              failedReportCount,
              failedReportCount,
          ),
      onClick = onExportClick,
  )

  Spacer(modifier = Modifier.height(16.dp))

  ActionButtonWithDescription(
      text = stringResource(R.string.action_delete_failed_reports),
      description =
          pluralStringResource(
              R.plurals.failed_reports_delete_summary,
              failedReportCount,
              failedReportCount,
          ),
      onClick = onDeleteClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
          ),
  )
}

@Composable
private fun SavedIndicator(visible: Boolean) {
  AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(end = 12.dp),
    ) {
      Icon(
          painter = painterResource(R.drawable.check_24px),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
      )
      Text(
          text = stringResource(R.string.indicator_saved),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun CredentialStatusCard(isBlocked: Boolean, consecutiveAuthFailures: Int) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          text = stringResource(R.string.preference_credential_status),
          style = MaterialTheme.typography.titleSmall,
      )
      Spacer(modifier = Modifier.height(4.dp))

      val (text, color) =
          when {
            isBlocked ->
                stringResource(R.string.preference_credential_status_blocked) to
                    MaterialTheme.colorScheme.error
            consecutiveAuthFailures > 0 ->
                pluralStringResource(
                    R.plurals.preference_credential_status_failures,
                    consecutiveAuthFailures,
                    consecutiveAuthFailures,
                    Config.Submission.MAX_AUTH_FAILURES,
                ) to MaterialTheme.colorScheme.onSurfaceVariant
            else ->
                stringResource(R.string.preference_credential_status_ok) to
                    MaterialTheme.colorScheme.onSurface
          }

      Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
  }
}

@Composable
fun SettingsTextFieldItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    validate: (String) -> String? = { null },
) {
  var passwordVisible by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val error = if (!isFocused) validate(value) else null

  val fieldModifier =
      modifier.fillMaxWidth().let {
        if (focusRequester != null) it.focusRequester(focusRequester) else it
      }

  val hidePasswordDescription = stringResource(R.string.action_hide_password)
  val showPasswordDescription = stringResource(R.string.action_show_password)

  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      modifier = fieldModifier,
      interactionSource = interactionSource,
      singleLine = true,
      visualTransformation =
          if (isPassword && !passwordVisible) PasswordVisualTransformation()
          else VisualTransformation.None,
      trailingIcon =
          if (isPassword) {
            {
              val painter =
                  if (passwordVisible) {
                    painterResource(R.drawable.visibility_24px)
                  } else {
                    painterResource(R.drawable.visibility_off_24px)
                  }

              val description =
                  if (passwordVisible) hidePasswordDescription else showPasswordDescription

              IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(painter = painter, contentDescription = description)
              }
            }
          } else null,
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      isError = error != null,
      supportingText = error?.let { { Text(it) } },
  )
}
