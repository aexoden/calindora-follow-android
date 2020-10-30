package com.calindora.follow

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private const val FEET_PER_METER = 3.2808399

class MainActivity : AppCompatActivity() {
    private var mService: FollowService? = null
    private var mHasPermissions = false

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FollowService.FollowBinder
            mService = binder.getService()
            mService?.registerActivity(this@MainActivity)
            findViewById<ToggleButton>(R.id.activity_main_button_service).isChecked = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mService = null
        }
    }

    private val mRequestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            mHasPermissions = isGranted
        }

    /*
     * Activity Methods
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toggleService: ToggleButton = findViewById(R.id.activity_main_button_service)
        toggleService.setOnCheckedChangeListener { _, isChecked -> onButtonService(isChecked) }
    }

    override fun onStart() {
        super.onStart()
        bindService()
        mService?.registerActivity(this)
    }

    override fun onStop() {
        super.onStop()
        mService?.unregisterActivity()
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
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) -> {
                    startService()
                }
                else -> {
                    mRequestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    findViewById<ToggleButton>(R.id.activity_main_button_service).isChecked = false
                }
            }
        } else {
            stopService()
        }
    }

    /*
     * Public Methods
     */

    fun updateDisplay() {
        val location = mService?.location ?: return

        findViewById<TextView>(R.id.activity_main_status_gps_time).text = String.format("%tc", location.time)
        findViewById<TextView>(R.id.activity_main_status_latitude).text = String.format("%.5f°", location.latitude)
        findViewById<TextView>(R.id.activity_main_status_longitude).text = String.format("%.5f°", location.longitude)
        findViewById<TextView>(R.id.activity_main_status_elevation).text = String.format("%.2f ft", location.altitude * FEET_PER_METER)
        findViewById<TextView>(R.id.activity_main_status_speed).text = String.format("%.2f mph", location.speed * FEET_PER_METER * 60.0 * 60.0 / 5280.0)
        findViewById<TextView>(R.id.activity_main_status_bearing).text = String.format("%.2f°", location.bearing)
        findViewById<TextView>(R.id.activity_main_status_accuracy).text = String.format("%.2f ft", location.accuracy * FEET_PER_METER)
    }

    /*
     * Private Methods
     */

    private fun bindService() {
        if (mService == null) {
            Intent(this, FollowService::class.java).also { intent ->
                bindService(intent, mConnection, 0)
            }
        }
    }

    private fun unbindService() {
        if (mService != null) {
            unbindService(mConnection)
            mService = null
        }
    }

    private fun startService() {
        Intent(this, FollowService::class.java).also { intent ->
            startForegroundService(intent)
        }
        bindService()
    }

    private fun stopService() {
        unbindService()
        Intent(this, FollowService::class.java).also { intent ->
            stopService(intent)
        }
    }
}