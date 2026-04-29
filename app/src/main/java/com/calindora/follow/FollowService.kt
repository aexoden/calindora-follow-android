package com.calindora.follow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Binder
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val LOG_FILE_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault())
private val BODY_TIMESTAMP_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)
private val SIGNATURE_TIMESTAMP_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)

class FollowService : Service() {
  private val binder = FollowBinder()

  private var locationUpdateCallback: ((Location) -> Unit)? = null

  var location: Location =
      Location("").apply {
        latitude = 0.0
        longitude = 0.0
        altitude = 0.0
        speed = 0f
        bearing = 0f
        accuracy = 0f
        time = System.currentTimeMillis()
      }
    private set

  private var lastReportElapsed = 0L

  private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val locationReportDao by lazy { AppDatabase.getInstance(this).locationReportDao() }

  private var nmeaLog: BufferedWriter? = null

  var tracking = false

  var logging: Boolean = false
    set(value) {
      var success = true
      if (!field && value) {
        success = startLogging()
      } else if (field && !value) {
        stopLogging()
      }

      field =
          if (success) {
            value
          } else {
            field
          }
    }

  private val locationListener = LocationListener { location -> updateLocation(location) }

  private val nmeaListener = OnNmeaMessageListener { nmea, _ -> logNmea(nmea) }

  /*
   * Callback Methods
   */

  fun setLocationUpdateCallback(callback: (Location) -> Unit) {
    locationUpdateCallback = callback
    callback(location)
  }

  fun unregisterLocationCallback() {
    locationUpdateCallback = null
  }

  /*
   * Service Methods
   */

  override fun onCreate() {
    super.onCreate()

    createNotificationChannel()
    createNotification()
    startLocationUpdates()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  override fun onBind(intent: Intent): IBinder {
    return binder
  }

  override fun onDestroy() {
    super.onDestroy()
    serviceScope.cancel()
    stopLocationUpdates()
    stopLogging()
    locationUpdateCallback = null
  }

  /*
   * Private Methods
   */

  private fun createNotification() {
    val intent =
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

    val pendingIntent = PendingIntent.getActivity(this, 0, intent, FLAG_IMMUTABLE)

    val notification =
        Notification.Builder(this, "com.calindora.follow.default")
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

  private fun logNmea(nmea: String) {
    try {
      nmeaLog?.apply {
        write(nmea)
        flush()
      }
    } catch (_: IOException) {
      stopLogging()
      logging = false
    }
  }

  private fun prepareLog(): Boolean {
    if (!isExternalStorageWritable()) {
      return false
    }

    try {
      val file =
          File(getExternalFilesDir("logs"), LOG_FILE_FORMATTER.format(Instant.now()) + ".log")
      file.createNewFile()

      nmeaLog = BufferedWriter(FileWriter(file))
    } catch (_: IOException) {
      return false
    }

    return true
  }

  private fun startLocationUpdates() {
    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?

    if (
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      locationManager?.requestLocationUpdates(
          LocationManager.GPS_PROVIDER,
          0L,
          0.0f,
          locationListener,
      )
    }
  }

  private fun stopLocationUpdates() {
    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?
    locationManager?.removeUpdates(locationListener)
  }

  private fun startLogging(): Boolean {
    if (!prepareLog()) {
      return false
    }

    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?

    if (
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      locationManager?.addNmeaListener(nmeaListener, null)
    }

    return true
  }

  private fun stopLogging() {
    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager?
    locationManager?.removeNmeaListener(nmeaListener)
    nmeaLog?.close()
    nmeaLog = null
  }

  private fun updateLocation(location: Location) {
    this.location = location

    locationUpdateCallback?.invoke(location)

    val nowElapsed = SystemClock.elapsedRealtime()
    if (tracking && nowElapsed - lastReportElapsed >= Config.Tracking.UPDATE_INTERVAL_MS) {
      serviceScope.launch {
        val report = Report(location)
        val signatureInput = report.formatSignatureInput()
        val body = FollowJson.encodeToString(report.toPayload())

        val reportEntity =
            LocationReportEntity(
                timestamp = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                bearing = location.bearing,
                accuracy = location.accuracy,
                signatureInput = signatureInput,
                body = body,
            )

        locationReportDao.insert(reportEntity)

        scheduleSubmissionWork()
      }

      lastReportElapsed = nowElapsed
    }
  }

  private fun scheduleSubmissionWork() {
    WorkManager.getInstance(this)
        .enqueueUniqueWork(
            SubmissionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            SubmissionWorker.buildWorkRequest(),
        )
  }

  /*
   * Inner Classes
   */

  class Report(private val location: Location) {
    fun toPayload(): LocationReportPayload =
        LocationReportPayload(
            timestamp = formatTimestamp(location.time),
            latitude = formatNumber(location.latitude),
            longitude = formatNumber(location.longitude),
            altitude = formatNumber(location.altitude),
            speed = formatNumber(location.speed.toDouble()),
            bearing = formatNumber(location.bearing.toDouble()),
            accuracy = formatNumber(location.accuracy.toDouble()),
        )

    fun formatSignatureInput(): String = buildString {
      append(formatTimestampSignature(location.time))
      append(formatNumber(location.latitude))
      append(formatNumber(location.longitude))
      append(formatNumber(location.altitude))
      append(formatNumber(location.speed.toDouble()))
      append(formatNumber(location.bearing.toDouble()))
      append(formatNumber(location.accuracy.toDouble()))
    }

    private fun formatNumber(number: Double): String {
      val output = String.format(Locale.US, "%.12f", number)

      if (output == "-0.000000000000") {
        return "0.000000000000"
      }

      return output
    }

    private fun formatTimestamp(timestamp: Long): String =
        BODY_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp))

    private fun formatTimestampSignature(timestamp: Long): String =
        SIGNATURE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp))
  }

  inner class FollowBinder : Binder() {
    fun getService(): FollowService = this@FollowService
  }
}
