package com.calindora.follow

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private const val SAVED_INDICATOR_VISIBLE_MS = 1500L

class SettingsActivity : AppCompatActivity() {
  private val viewModel: SettingsViewModel by viewModels { SettingsViewModelFactory(application) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    WindowCompat.setDecorFitsSystemWindows(window, false)

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
      delay(SAVED_INDICATOR_VISIBLE_MS)
      savedIndicatorVisible = false
    }
  }

  // Display toast messages for one-shot operations
  LaunchedEffect(uiState.toastMessage) {
    uiState.toastMessage?.let {
      Toast.makeText(context, it, Toast.LENGTH_LONG).show()
      viewModel.clearToastMessage()
    }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.action_settings)) },
            navigationIcon = {
              IconButton(onClick = { activity?.finish() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "Back",
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
              text = "Connection",
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
                when {
                  value.isBlank() -> "URL required"
                  !value.startsWith("https://") -> "URL must use HTTPS"
                  else -> null
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
              uiState.consecutiveAuthFailures >= SubmissionWorker.MAX_AUTH_FAILURES
      ) {
        Button(
            onClick = { viewModel.showResetDialog() },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.preference_reset_credential_block))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.preference_reset_credential_block_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
      }

      // Failed report management
      if (uiState.failedReportCount > 0) {
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.showRetryDialog() },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Retry Failed Reports")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text =
                "Move ${uiState.failedReportCount} retryable reports back into the submission queue",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.showExportDialog() },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Export Failed Reports")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Export ${uiState.failedReportCount} failed reports to log file",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.showDeleteDialog() },
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
        ) {
          Text("Delete Failed Reports")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Permanently delete ${uiState.failedReportCount} failed reports",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }

  // Reset Confirmation Dialog
  if (uiState.showResetDialog) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissResetDialog() },
        title = { Text("Reset Authentication Block") },
        text = {
          Text(
              "This will re-enable submissions. " +
                  "Make sure you've fixed any credential issues before proceeding."
          )
        },
        confirmButton = {
          TextButton(onClick = { viewModel.resetCredentialBlock() }) { Text("Reset") }
        },
        dismissButton = {
          TextButton(onClick = { viewModel.dismissResetDialog() }) { Text("Cancel") }
        },
    )
  }

  // Retry Failed Reports Confirmation Dialog
  if (uiState.showRetryDialog) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissRetryDialog() },
        title = { Text("Retry Failed Reports") },
        text = {
          Text(
              "${uiState.failedReportCount} reports will be moved back to the submission queue " +
                  "with a fresh attempt budget. Reports that hit a real bug (rather than a transient " +
                  "issue or fixed configuration) will likely fail again. If submissions are currently " +
                  "blocked due to authentication failures, you'll also need to reset that block."
          )
        },
        confirmButton = {
          TextButton(onClick = { viewModel.retryFailedReports() }) { Text("Retry") }
        },
        dismissButton = {
          TextButton(onClick = { viewModel.dismissRetryDialog() }) { Text("Cancel") }
        },
    )
  }

  // Export Failed Reports Dialog
  if (uiState.showExportDialog) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissExportDialog() },
        title = { Text("Export Failed Reports") },
        text = {
          Text(
              "This will export ${uiState.failedReportCount} failed reports to the logs directory."
          )
        },
        confirmButton = {
          TextButton(onClick = { viewModel.exportFailedReports() }) { Text("Export") }
        },
        dismissButton = {
          TextButton(onClick = { viewModel.dismissExportDialog() }) { Text("Cancel") }
        },
    )
  }

  // Delete Failed Reports Dialog
  if (uiState.showDeleteDialog) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissDeleteDialog() },
        title = { Text("Delete Failed Reports") },
        text = {
          Text(
              "This will permanently delete ${uiState.failedReportCount} failed reports. " +
                  "This action cannot be undone."
          )
        },
        confirmButton = {
          TextButton(onClick = { viewModel.deleteFailedReports() }) { Text("Delete") }
        },
        dismissButton = {
          TextButton(onClick = { viewModel.dismissDeleteDialog() }) { Text("Cancel") }
        },
    )
  }
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
          text = "Saved",
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
                stringResource(
                    R.string.preference_credential_status_failures,
                    consecutiveAuthFailures,
                    SubmissionWorker.MAX_AUTH_FAILURES,
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

              val description = if (passwordVisible) "Hide password" else "Show password"

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
