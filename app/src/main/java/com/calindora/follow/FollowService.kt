package com.calindora.follow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Binder
import android.os.Environment
import android.os.IBinder
import androidx.preference.PreferenceManager
import androidx.work.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val UPDATE_INTERVAL = 5000

class FollowService : Service() {
    private val mBinder = FollowBinder()

    private var mActivity: MainActivity? = null
    private lateinit var mLocation: Location
    private var mLastReportTime = 0L

    private var mNmeaLog: BufferedWriter? = null

    var tracking = false

    var logging: Boolean = false
        set(value) {
            var success = true

            if (!field && value) {
                success = startLogging()
            } else if (field && !value) {
                stopLogging()
            }

            field = if (success) {
                value
            } else {
                field
            }

        }

    private val mLocationListener =
        LocationListener { location -> updateLocation(location) }

    private val mNmeaListener =
        OnNmeaMessageListener { nmea, timestamp -> logNmea(nmea, timestamp) }

    val location: Location
        get() = mLocation

    /*
     * Service Methods
     */

    override fun onCreate() {
        createNotificationChannel()
        createNotification()
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopLogging()
    }

    /*
     * Public Methods
     */

    fun registerActivity(activity: MainActivity) {
        mActivity = activity
    }

    fun unregisterActivity() {
        mActivity = null
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

    private fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    private fun logNmea(nmea: String, timestamp: Long) {
        mNmeaLog?.write(nmea)
    }

    private fun prepareLog(): Boolean {
        if (!isExternalStorageWritable()) {
            return false
        }

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
            val file = File(getExternalFilesDir("logs"), dateFormat.format(Date()) + ".log")
            file.createNewFile()

            mNmeaLog = BufferedWriter(FileWriter(file))
        } catch (e: IOException) {
            return false
        }

        return true
    }

    private fun startLocationUpdates() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?

        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, mLocationListener)
        } catch (e: SecurityException) {
        }
    }

    private fun stopLocationUpdates() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?
        locationManager?.removeUpdates(mLocationListener)
    }

    private fun startLogging(): Boolean {
        if (!prepareLog()) {
            return false
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?

        try {
            locationManager?.addNmeaListener(mNmeaListener, null)
        } catch (e: SecurityException) {
        }

        return true
    }

    private fun stopLogging() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?
        locationManager?.removeNmeaListener(mNmeaListener)
        mNmeaLog?.close()
    }

    private fun updateLocation(location: Location) {
        mLocation = location

        if (tracking && location.time > mLastReportTime + UPDATE_INTERVAL) {
            val report = Report(location)
            val url = report.formatUrl()
            val parameters = report.formatParameters()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SubmissionWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(workDataOf(
                    "url" to url,
                    "parameters" to parameters
                ))
                .build()

            WorkManager.getInstance(this)
                .enqueueUniqueWork("submission", ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)

            mLastReportTime = location.time
        }

        mActivity?.updateDisplay()
    }

    /*
     * Inner Classes
     */

    inner class Report(private val location: Location) {
        private val timestamp: String get() = formatTimestamp(location.time)
        private val latitude: String get() = formatNumber(location.latitude)
        private val longitude: String get() = formatNumber(location.longitude)
        private val altitude: String get() = formatNumber(location.altitude)
        private val speed: String get() = formatNumber(location.speed.toDouble())
        private val bearing: String get() = formatNumber(location.bearing.toDouble())
        private val accuracy: String get() = formatNumber(location.accuracy.toDouble())

        /*
         * Public Methods
         */

        fun formatUrl(): String {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this@FollowService)
            val url = preferences.getString("preference_url", "") ?: return ""
            val key = preferences.getString("preference_device_key", "") ?: return ""
            return String.format("%s/api/v1/devices/%s/reports", url, key)
        }

        fun formatParameters(): String {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this@FollowService)
            val secret = preferences.getString("preference_device_secret", "") ?: return ""

            val parameters = StringBuilder()

            parameters.append("timestamp=").append(URLEncoder.encode(timestamp, "UTF-8"))
            parameters.append("&latitude=").append(latitude)
            parameters.append("&longitude=").append(longitude)
            parameters.append("&altitude=").append(altitude)
            parameters.append("&speed=").append(speed)
            parameters.append("&bearing=").append(bearing)
            parameters.append("&accuracy=").append(accuracy)
            parameters.append("&signature=").append(formatSignature(secret))

            return parameters.toString()
        }

        /*
         * Private Methods
         */

        private fun formatNumber(number: Double): String {
            return String.format(Locale.US, "%.12f", number)
        }

        private fun formatSignature(secret: String): String {
            val input = StringBuilder()

            input.append(timestamp)
            input.append(latitude)
            input.append(longitude)
            input.append(altitude)
            input.append(speed)
            input.append(bearing)
            input.append(accuracy)

            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), mac.algorithm)
            mac.init(key)

            val digest = mac.doFinal(input.toString().toByteArray(Charsets.UTF_8))

            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+00:00", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            return dateFormat.format(Date(timestamp))
        }
    }

    inner class FollowBinder : Binder() {
        fun getService(): FollowService = this@FollowService
    }
}