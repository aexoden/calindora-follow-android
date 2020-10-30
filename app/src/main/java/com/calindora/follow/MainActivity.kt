package com.calindora.follow

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private const val FEET_PER_METER = 3.2808399

class MainActivity : AppCompatActivity() {
    private lateinit var mBinder: FollowService.FollowBinder
    private var mBound = false

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mBound = true
            mBinder = service as FollowService.FollowBinder
            mBinder.getService().registerActivity(this@MainActivity)
            findViewById<ToggleButton>(R.id.activity_main_button_service).isChecked = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    private val mRequestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> }

    /*
     * Activity Methods
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toggleService: ToggleButton = findViewById(R.id.activity_main_button_service)
        toggleService.setOnCheckedChangeListener { _, isChecked -> onButtonService(isChecked) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
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
        if (!mBound) {
            return
        }

        val location = mBinder.getService().location

        findViewById<TextView>(R.id.activity_main_status_gps_time).text = String.format("%tc", location.time)
        findViewById<TextView>(R.id.activity_main_status_latitude).text = String.format("%.5f°", location.latitude)
        findViewById<TextView>(R.id.activity_main_status_longitude).text = String.format("%.5f°", location.longitude)
        findViewById<TextView>(R.id.activity_main_status_elevation).text =
            String.format("%.2f ft", location.altitude * FEET_PER_METER)
        findViewById<TextView>(R.id.activity_main_status_speed).text =
            String.format("%.2f mph", location.speed * FEET_PER_METER * 60.0 * 60.0 / 5280.0)
        findViewById<TextView>(R.id.activity_main_status_bearing).text = String.format("%.2f°", location.bearing)
        findViewById<TextView>(R.id.activity_main_status_accuracy).text =
            String.format("%.2f ft", location.accuracy * FEET_PER_METER)
    }

    /*
     * Private Methods
     */

    private fun bindService() {
        if (!mBound) {
            Intent(this, FollowService::class.java).also { intent ->
                bindService(intent, mConnection, 0)
            }
        }
    }

    private fun unbindService() {
        if (mBound) {
            mBinder.getService().unregisterActivity()
            unbindService(mConnection)
            mBound = false
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