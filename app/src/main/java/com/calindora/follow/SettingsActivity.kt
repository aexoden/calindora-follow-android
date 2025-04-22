package com.calindora.follow

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager

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
    val scrollState = rememberScrollState()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Display toast messages for operations
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearToastMessage()
        }
    }

    // Register preference change listener
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val prefListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED) {
                viewModel.updateCredentialBlockedStatus(
                    sharedPrefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
                )
            }
        }
    }

    DisposableEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.action_settings)) }) }) {
        paddingValues ->
        Column(
            modifier =
                Modifier.padding(paddingValues)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
        ) {
            // Service URL
            SettingsTextFieldItem(
                title = stringResource(R.string.preference_url),
                value = uiState.serviceUrl,
                onValueChanged = viewModel::updateServiceUrl,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device Key
            SettingsTextFieldItem(
                title = stringResource(R.string.preference_device_key),
                value = uiState.deviceKey,
                onValueChanged = viewModel::updateDeviceKey,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device Secret
            SettingsTextFieldItem(
                title = stringResource(R.string.preference_device_secret),
                value = uiState.deviceSecret,
                onValueChanged = viewModel::updateDeviceSecret,
                isPassword = true,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Status Category
            Text(
                text = stringResource(R.string.preference_category_status),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Credential Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.preference_credential_status),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    when {
                        uiState.isCredentialBlocked -> {
                            Text(
                                text =
                                    stringResource(R.string.preference_credential_status_blocked),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Red,
                            )
                        }

                        uiState.authFailureCount > 0 -> {
                            Text(
                                text =
                                    "${uiState.authFailureCount} reports with authentication failures",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.DarkGray,
                            )
                        }

                        else -> {
                            Text(
                                text = stringResource(R.string.preference_credential_status_ok),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Green,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reset Credential Block Button (visible if blocked or enough auth failures)
            if (
                uiState.isCredentialBlocked ||
                    uiState.authFailureCount >= SubmissionWorker.MAX_AUTH_FAILURES
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Export Failed Reports Button (visible if any failed reports)
            if (uiState.failedReportCount > 0) {
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Delete Failed Reports Button
                Button(
                    onClick = { viewModel.showDeleteDialog() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) {
                    Text("Delete Failed Reports")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Permanently delete ${uiState.failedReportCount} failed reports",
                    style = MaterialTheme.typography.bodySmall,
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
                    "This will reset all authentication failures and re-enable submissions. Make sure you've fixed any credential issues before proceeding."
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
                TextButton(
                    onClick = {
                        viewModel.exportFailedReports()
                    }
                ) {
                    Text("Export")
                }
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
                    "This will permanently delete ${uiState.failedReportCount} failed reports. This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFailedReports()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteDialog() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun SettingsTextFieldItem(
    title: String,
    value: String,
    onValueChanged: (String) -> Unit,
    isPassword: Boolean = false,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (isPassword) {
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    val image =
                        if (passwordVisible) {
                            ImageVector.vectorResource(R.drawable.baseline_visibility_24)
                        } else {
                            ImageVector.vectorResource(R.drawable.baseline_visibility_off_24)
                        }

                    val description = if (passwordVisible) "Hide password" else "Show password"

                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = description)
                    }
                },
            )
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}
