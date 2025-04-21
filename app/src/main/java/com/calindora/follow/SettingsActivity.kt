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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

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
    var authFailureCount by remember {
        mutableIntStateOf(prefs.getInt(SubmissionWorker.PREF_CONSECUTIVE_UNAUTHORIZED, 0))
    }

    var showResetDialog by remember { mutableStateOf(false) }

    val prefListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED) {
                blocked = sharedPrefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
            } else if (key == SubmissionWorker.PREF_CONSECUTIVE_UNAUTHORIZED) {
                authFailureCount =
                    sharedPrefs.getInt(SubmissionWorker.PREF_CONSECUTIVE_UNAUTHORIZED, 0)
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
                    } else if (authFailureCount > 0) {
                        Text(
                            text =
                                "Authentication warnings: $authFailureCount consecutive failures",
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

            // Reset Credential Block Button
            if (blocked) {
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
                    "This will clear the authentication failure counter and re-enable submissions. Make sure you've fixed any credential issues before proceeding."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        resetCredentialBlock(context)
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } },
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

        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation =
                if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

private fun resetCredentialBlock(context: Context) {
    SubmissionWorker.resetUnauthorizedCounter(context)

    Toast.makeText(context, "Authentication block reset. Retrying submissions.", Toast.LENGTH_LONG)
        .show()

    val workRequest = OneTimeWorkRequestBuilder<SubmissionWorker>().build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork("settings_reset_retry", ExistingWorkPolicy.REPLACE, workRequest)
}
