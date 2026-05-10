package com.calindora.follow

import android.Manifest
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FollowService : Service(), FollowServiceHandle {
  private val binder = FollowBinder()

  data class ServiceState(
      val tracking: Boolean = false,
      val logging: Boolean = false,
      val location: Location? = null,
  )

  private val _state = MutableStateFlow(ServiceState())
  override val state: StateFlow<ServiceState> = _state.asStateFlow()

  private var lastReportElapsed = 0L

  private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val locationReportDao by lazy { appContainer.locationReportDao }
  private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager? }

  private var nmeaLog: BufferedWriter? = null

  /** Whether tracked location reports are currently being queued for submission. */
  override var tracking: Boolean
    get() = _state.value.tracking
    set(value) {
      _state.update { it.copy(tracking = value) }
    }

  /**
   * Enable or disable NMEA logging.
   *
   * @return true if the requested state is now active. Returns false only when [enabled] is true
   *   and logging could not be started (e.g. external storage is unavailable or the log file could
   *   not be created).
   */
  override fun setLogging(enabled: Boolean): Boolean {
    if (_state.value.logging == enabled) return true

    if (enabled) {
      if (!startLogging()) return false
    } else {
      stopLogging()
    }

    _state.update { it.copy(logging = enabled) }
    return true
  }

  private val locationListener = LocationListener { location -> updateLocation(location) }

  private val nmeaListener = OnNmeaMessageListener { nmea, _ -> logNmea(nmea) }

  /*
   * Service Methods
   */

  override fun onCreate() {
    super.onCreate()

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
        NotificationCompat.Builder(this, Notifications.ChannelIds.DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .build()

    startForeground(Notifications.Ids.FOREGROUND, notification)
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
    } catch (e: IOException) {
      Log.w("FollowService", "NMEA logging stopped due to I/O error", e)
      setLogging(false)
    }
  }

  private fun prepareLog(): Boolean {
    if (!isExternalStorageWritable()) {
      Log.w("FollowService", "Cannot start NMEA logging: external storage is not mounted")
      return false
    }

    try {
      val file =
          File(
              getExternalFilesDir("logs"),
              Formatters.LOG_FILE_TIMESTAMP.format(Instant.now()) + ".log",
          )
      file.createNewFile()

      nmeaLog = BufferedWriter(FileWriter(file))
    } catch (e: IOException) {
      Log.w("FollowService", "Cannot start NMEA logging: failed to create log file", e)
      return false
    }

    return true
  }

  private fun startLocationUpdates() {
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
    locationManager?.removeUpdates(locationListener)
  }

  private fun startLogging(): Boolean {
    if (!prepareLog()) {
      return false
    }

    if (
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      locationManager?.addNmeaListener(nmeaListener, null)
    }

    return true
  }

  private fun stopLogging() {
    locationManager?.removeNmeaListener(nmeaListener)
    nmeaLog?.close()
    nmeaLog = null
  }

  private fun updateLocation(location: Location) {
    _state.update { it.copy(location = location) }

    val nowElapsed = SystemClock.elapsedRealtime()
    if (tracking && nowElapsed - lastReportElapsed >= Config.Tracking.UPDATE_INTERVAL_MS) {
      serviceScope.launch {
        val reportEntity =
            LocationReportEntity(
                timestamp = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                bearing = location.bearing,
                accuracy = location.accuracy,
            )

        locationReportDao.insert(reportEntity)

        scheduleSubmissionWork()
      }

      lastReportElapsed = nowElapsed
    }
  }

  private fun scheduleSubmissionWork() {
    appContainer.workManager.enqueueUniqueWork(
        SubmissionWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        SubmissionWorker.buildWorkRequest(),
    )
  }

  /*
   * Inner Classes
   */

  inner class FollowBinder : Binder() {
    fun getService(): FollowService = this@FollowService
  }
}
