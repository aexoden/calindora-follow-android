package com.calindora.follow

import android.Manifest
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Binder
import android.os.Environment
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.work.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent, FLAG_IMMUTABLE)

        val notification = Notification.Builder(this, "com.calindora.follow.default")
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_text))
            .setContentIntent(pendingIntent)
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

    @Suppress("UNUSED_PARAMETER")
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

        when (PackageManager.PERMISSION_GRANTED) {
            // It should be impossible for this to fail, since the startLocationUpdates call itself was gated by a permission check.
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) -> {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, mLocationListener)
            }
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

        when (PackageManager.PERMISSION_GRANTED) {
            // It should be impossible for this to fail, since the startLogging call itself was gated by a permission check.
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) -> {
                locationManager?.addNmeaListener(mNmeaListener, null)
            }
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
            val signatureInput = report.formatSignatureInput()
            val body = report.formatBody()

            val workRequest = OneTimeWorkRequestBuilder<SubmissionWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(
                    workDataOf(
                        "signatureInput" to signatureInput,
                        "body" to body
                    )
                )
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
        private val timestamp: Long get() = location.time
        private val latitude: String get() = formatNumber(location.latitude)
        private val longitude: String get() = formatNumber(location.longitude)
        private val altitude: String get() = formatNumber(location.altitude)
        private val speed: String get() = formatNumber(location.speed.toDouble())
        private val bearing: String get() = formatNumber(location.bearing.toDouble())
        private val accuracy: String get() = formatNumber(location.accuracy.toDouble())

        /*
         * Public Methods
         */

        fun formatBody(): String {
            val body = StringBuilder()

            body.append("{")
            body.append("\"timestamp\": \"").append(formatTimestamp(timestamp)).append("\",\n")
            body.append("\"latitude\": \"").append(latitude).append("\",\n")
            body.append("\"longitude\": \"").append(longitude).append("\",\n")
            body.append("\"altitude\": \"").append(altitude).append("\",\n")
            body.append("\"speed\": \"").append(speed).append("\",\n")
            body.append("\"bearing\": \"").append(bearing).append("\",\n")
            body.append("\"accuracy\": \"").append(accuracy).append("\"\n")
            body.append("}")

            return body.toString()
        }

        fun formatSignatureInput(): String {
            val input = StringBuilder()

            input.append(formatTimestampSignature(timestamp))
            input.append(latitude)
            input.append(longitude)
            input.append(altitude)
            input.append(speed)
            input.append(bearing)
            input.append(accuracy)

            return input.toString()
        }

        /*
         * Private Methods
         */

        private fun formatNumber(number: Double): String {
            val output = String.format(Locale.US, "%.12f", number)

            if (output == "-0.000000000000") {
                return "0.000000000000"
            }

            return output
        }

        private fun formatTimestamp(timestamp: Long): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS+00:00", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            return dateFormat.format(Date(timestamp))
        }

        private fun formatTimestampSignature(timestamp: Long): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+00:00", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            return dateFormat.format(Date(timestamp))
        }
    }

    inner class FollowBinder : Binder() {
        fun getService(): FollowService = this@FollowService
    }
}
