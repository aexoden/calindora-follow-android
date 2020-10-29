package com.calindora.follow

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.ToggleButton

class MainActivity : Activity() {
    private var mService: FollowService? = null

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FollowService.FollowBinder
            mService = binder.getService()
            findViewById<ToggleButton>(R.id.activity_main_button_service).isChecked = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mService = null
        }
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
            startService()
        } else {
            stopService()
        }
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