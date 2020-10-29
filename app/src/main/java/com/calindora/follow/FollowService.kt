package com.calindora.follow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class FollowService : Service() {
    private val mBinder = FollowBinder()

    /*
     * Service Methods
     */

    override fun onCreate() {
        createNotificationChannel()
        createNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    /*
     * Private Methods
     */

    private fun createNotification() {
        val notification = Notification.Builder(this, "com.calindora.follow.default")
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_text))
            .build()

        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.notification_channel_name)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel("com.calindora.follow.default", name, importance)

        channel.description = getString(R.string.notification_channel_description)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /*
     * Inner Classes
     */

    inner class FollowBinder : Binder() {
        fun getService(): FollowService = this@FollowService
    }
}