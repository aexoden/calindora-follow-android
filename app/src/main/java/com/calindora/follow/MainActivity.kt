package com.calindora.follow

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import java.util.*

private const val FEET_PER_METER = 3.2808399

class MainActivity : AppCompatActivity() {
    private lateinit var locationViewModel: LocationViewModel
    private lateinit var mBinder: FollowService.FollowBinder

    private var serviceState by mutableStateOf(ServiceState())

    data class ServiceState(
        val isBound: Boolean = false,
        val isTracking: Boolean = false,
        val isLogging: Boolean = false,
        val location: Location? = null,
        val credentialWarningVisible: Boolean = false,
        val isTrackingPending: Boolean = false,
        val isLoggingPending: Boolean = false,
    )

    private val mConnection =
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as FollowService.FollowBinder
                mBinder = binder

                binder.getService().setLocationUpdateCallback { location ->
                    serviceState =
                        serviceState.copy(
                            isBound = true,
                            isTracking = binder.getService().tracking,
                            isLogging = binder.getService().logging,
                            location = location,
                            isTrackingPending = false,
                            isLoggingPending = false,
                        )
                }

                serviceState =
                    serviceState.copy(
                        isBound = true,
                        isTracking = binder.getService().tracking,
                        isLogging = binder.getService().logging,
                        location = binder.getService().location,
                    )
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                serviceState =
                    serviceState.copy(isBound = false, isTracking = false, isLogging = false)
            }
        }

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == SubmissionWorker.PREF_SUBMISSIONS_BLOCKED) {
                val blocked = prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
                serviceState = serviceState.copy(credentialWarningVisible = blocked)
            }
        }

    private val mRequestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /*
     * Activity Methods
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        locationViewModel = ViewModelProvider(this)[LocationViewModel::class.java]

        checkNotificationPermission()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val blocked = prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
        serviceState = serviceState.copy(credentialWarningVisible = blocked)

        setContent {
            CalindoraFollowTheme {
                MainScreen(
                    queueSize =
                        locationViewModel.queueSize
                            .collectAsStateWithLifecycle(initialValue = 0)
                            .value,
                    lastSubmissionTime =
                        locationViewModel.lastSubmissionTime
                            .collectAsStateWithLifecycle(initialValue = 0L)
                            .value,
                    onServiceToggle = { isChecked -> onButtonService(isChecked) },
                    onTrackToggle = { isChecked -> onButtonTrack(isChecked) },
                    onLogToggle = { isChecked -> onButtonLog(isChecked) },
                    onClearClick = { locationViewModel.clearQueue() },
                    onForceSyncClick = { locationViewModel.forceSubmission() },
                    onSettingsClick = {
                        val intent = Intent(this, SettingsActivity::class.java)
                        startActivity(intent)
                    },
                    isBound = serviceState.isBound,
                    isTracking = serviceState.isTracking,
                    isLogging = serviceState.isLogging,
                    isTrackingPending = serviceState.isTrackingPending,
                    isLoggingPending = serviceState.isLoggingPending,
                    locationData = serviceState.location,
                    credentialWarningVisible = serviceState.credentialWarningVisible,
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // We don't need this as we use Compose TopAppBar
        return true
    }

    override fun onResume() {
        super.onResume()
        updateCredentialWarning()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
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
            when (PackageManager.PERMISSION_GRANTED) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) -> {
                    startService()
                }
                else -> {
                    mRequestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        } else {
            if (serviceState.isBound) {
                mBinder.getService().logging = false
                mBinder.getService().tracking = false
            }

            stopService()
        }
    }

    private fun onButtonLog(isChecked: Boolean) {
        if (serviceState.isBound) {
            serviceState = serviceState.copy(isLogging = isChecked, isLoggingPending = true)

            mBinder.getService().logging = isChecked
        }
    }

    private fun onButtonTrack(isChecked: Boolean) {
        if (serviceState.isBound) {
            serviceState = serviceState.copy(isTracking = isChecked, isTrackingPending = true)

            mBinder.getService().tracking = isChecked
        }
    }

    private fun checkNotificationPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) -> {}
            else -> {
                mRequestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /*
     * Public Methods
     */

    /*
     * Private Methods
     */

    private fun bindService() {
        if (!serviceState.isBound) {
            Intent(this, FollowService::class.java).also { intent ->
                bindService(intent, mConnection, 0)
            }
        }
    }

    private fun unbindService() {
        if (serviceState.isBound) {
            mBinder.getService().unregisterLocationCallback()
            unbindService(mConnection)
            serviceState = serviceState.copy(isBound = false)
        }
    }

    private fun startService() {
        Intent(this, FollowService::class.java).also { intent -> startForegroundService(intent) }
        bindService()
    }

    private fun stopService() {
        if (serviceState.isBound) {
            unbindService()
        }

        Intent(this, FollowService::class.java).also { intent -> stopService(intent) }
    }

    private fun updateCredentialWarning() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val blocked = prefs.getBoolean(SubmissionWorker.PREF_SUBMISSIONS_BLOCKED, false)
        serviceState = serviceState.copy(credentialWarningVisible = blocked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    queueSize: Int,
    lastSubmissionTime: Long,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
    onClearClick: () -> Unit,
    onForceSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    isTrackingPending: Boolean,
    isLoggingPending: Boolean,
    locationData: Location?,
    credentialWarningVisible: Boolean,
) {
    var isDebugEnabled by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
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
            )

            Spacer(modifier = Modifier.height(8.dp))

            CredentialWarningBanner(isVisible = credentialWarningVisible)

            Spacer(modifier = Modifier.height(16.dp))

            ControlsSection(
                isBound = isBound,
                isTracking = isTracking,
                isLogging = isLogging,
                isTrackingPending = isTrackingPending,
                isLoggingPending = isLoggingPending,
                isDebugEnabled = isDebugEnabled,
                onServiceToggle = onServiceToggle,
                onTrackToggle = onTrackToggle,
                onLogToggle = onLogToggle,
                onDebugToggle = { isDebugEnabled = it },
                onClearClick = onClearClick,
                onForceSyncClick = onForceSyncClick,
            )
        }
    }
}

@Composable
fun LocationStatusSection(locationData: Location?, lastSubmissionTime: Long, queueSize: Int) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (locationData != null) {
                val locale = Locale.getDefault()

                StatusRow(
                    label = stringResource(R.string.label_gps_time),
                    value = String.format(locale, "%tc", locationData.time),
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
                Text("Waiting for location data...")
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
            color = Color(0xFFFFEEEE),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.credential_warning),
                color = Color(0xFFB71C1C),
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
    isTrackingPending: Boolean = false,
    isLoggingPending: Boolean = false,
    isDebugEnabled: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
    onDebugToggle: (Boolean) -> Unit,
    onClearClick: () -> Unit,
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
                isPending = isLoggingPending,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )

            ToggleButton(
                checked = isTracking,
                onCheckedChange = onTrackToggle,
                enabledText = stringResource(R.string.label_tracking_on),
                disabledText = stringResource(R.string.label_tracking_off),
                enabled = isBound,
                isPending = isTrackingPending,
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

        Button(
            onClick = onClearClick,
            enabled = isDebugEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.label_clear_queue))
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
    isPending: Boolean = false,
) {
    Button(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled && !isPending,
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
        if (isPending) {
            Row {
                Text(if (checked) enabledText else disabledText)
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        } else {
            Text(if (checked) enabledText else disabledText)
        }
    }
}

private fun formatSubmissionTime(timestamp: Long): String {
    return if (timestamp > 0) {
        String.format("%tc", timestamp)
    } else {
        "Never"
    }
}
