package com.calindora.follow

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent { CalindoraFollowTheme { SettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scope = rememberCoroutineScope()

    val locationReportDao = remember { AppDatabase.getInstance(context).locationReportDao() }
    val failedReportCount =
        locationReportDao
            .getPermanentlyFailedReportCount()
            .collectAsStateWithLifecycle(initialValue = 0)
    val authFailureCount =
        locationReportDao.getAuthFailureCount().collectAsStateWithLifecycle(initialValue = 0)

    var url by remember {
        mutableStateOf(
            prefs.getString("preference_url", "https://follow.calindora.com")
                ?: "https://follow.calindora.com"
        )
    }

    var deviceKey by remember { mutableStateOf(prefs.getString("preference_device_key", "") ?: "") }

    var deviceSecret by remember {
        mutableStateOf(prefs.getString("preference_device_secret", "") ?: "")
    }

    var blocked by remember {
        mutableStateOf(prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false))
    }

    var showResetDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val prefListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED) {
                blocked = sharedPrefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
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
                value = url,
                onValueChanged = { newValue ->
                    url = newValue
                    prefs.edit { putString("preference_url", newValue) }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device Key
            SettingsTextFieldItem(
                title = stringResource(R.string.preference_device_key),
                value = deviceKey,
                onValueChanged = { newValue ->
                    deviceKey = newValue
                    prefs.edit { putString("preference_device_key", newValue) }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device Secret
            SettingsTextFieldItem(
                title = stringResource(R.string.preference_device_secret),
                value = deviceSecret,
                onValueChanged = { newValue ->
                    deviceSecret = newValue
                    prefs.edit { putString("preference_device_secret", newValue) }
                },
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

                    if (blocked) {
                        Text(
                            text = stringResource(R.string.preference_credential_status_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Red,
                        )
                    } else if (authFailureCount.value > 0) {
                        Text(
                            text = "${authFailureCount.value} reports with authentication failures",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.DarkGray,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.preference_credential_status_ok),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Green,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reset Credential Block Button (visible if blocked or enough auth failures)
            if (blocked || authFailureCount.value >= SubmissionWorker.MAX_AUTH_FAILURES) {
                Button(onClick = { showResetDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.preference_reset_credential_block))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.preference_reset_credential_block_summary),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Export Failed Reports Button (visible if any failed reports)
            if (failedReportCount.value > 0) {
                Button(onClick = { showExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export Failed Reports")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Export ${failedReportCount.value} failed reports to log file",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Delete Failed Reports Button
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) {
                    Text("Delete Failed Reports")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Permanently delete ${failedReportCount.value} failed reports",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Reset Confirmation Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Authentication Block") },
            text = {
                Text(
                    "This will reset all authentication failures and re-enable submissions. Make sure you've fixed any credential issues before proceeding."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        scope.launch { resetCredentialBlock(context) }
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } },
        )
    }

    // Export Failed Reports Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Failed Reports") },
            text = {
                Text(
                    "This will export ${failedReportCount.value} failed reports to the logs directory."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        scope.launch { exportFailedReports(context) }
                    }
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Delete Failed Reports Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Failed Reports") },
            text = {
                Text(
                    "This will permanently delete ${failedReportCount.value} failed reports. This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch { deleteFailedReports(context) }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
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

private suspend fun resetCredentialBlock(context: Context) {
    val dao = AppDatabase.getInstance(context).locationReportDao()
    dao.resetPermanentlyFailedReports()

    PreferenceManager.getDefaultSharedPreferences(context).edit {
        putBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
    }

    Toast.makeText(context, "Authentication block reset. Retrying submissions.", Toast.LENGTH_LONG)
        .show()

    val workRequest = OneTimeWorkRequestBuilder<SubmissionWorker>().build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("settings_reset_retry", ExistingWorkPolicy.REPLACE, workRequest)
}

private suspend fun exportFailedReports(context: Context) {
    val success = SubmissionWorker.exportFailedReports(context)
    val message =
        if (success) {
            "Failed reports exported to logs directory"
        } else {
            "Failed to export reports"
        }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private suspend fun deleteFailedReports(context: Context) {
    val dao = AppDatabase.getInstance(context).locationReportDao()
    dao.deletePermanentlyFailedReports()
    Toast.makeText(context, "Failed reports deleted", Toast.LENGTH_LONG).show()
}
