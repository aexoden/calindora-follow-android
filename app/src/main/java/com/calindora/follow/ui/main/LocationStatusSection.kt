package com.calindora.follow.ui.main

import android.location.Location
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.calindora.follow.DisplayPreferences
import com.calindora.follow.R
import com.calindora.follow.ui.components.StatusRow
import com.calindora.follow.ui.format.DISPLAY_FORMATTER
import com.calindora.follow.ui.format.formatCountdown
import com.calindora.follow.ui.format.formatSubmissionTime
import com.calindora.follow.ui.format.rememberCountdown
import java.time.Instant

@Composable
fun LocationStatusSection(
    locationData: Location?,
    lastSubmissionTime: Long,
    queueSize: Int,
    syncWorkInfo: WorkInfo?,
    displayPreferences: DisplayPreferences,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Column(modifier = Modifier.padding(16.dp)) {
      if (locationData != null) {
        val locale = LocalLocale.current.platformLocale
        val distanceAbbr = stringResource(displayPreferences.distanceUnit.abbreviationRes)
        val speedAbbr = stringResource(displayPreferences.speedUnit.abbreviationRes)

        StatusRow(
            label = stringResource(R.string.label_gps_time),
            value = DISPLAY_FORMATTER.format(Instant.ofEpochMilli(locationData.time)),
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
            value =
                String.format(
                    locale,
                    "%.2f %s",
                    displayPreferences.distanceUnit.fromMeters(locationData.altitude),
                    distanceAbbr,
                ),
        )
        StatusRow(
            label = stringResource(R.string.label_speed),
            value =
                String.format(
                    locale,
                    "%.2f %s",
                    displayPreferences.speedUnit.fromMetersPerSecond(locationData.speed.toDouble()),
                    speedAbbr,
                ),
        )
        StatusRow(
            label = stringResource(R.string.label_bearing),
            value = String.format(locale, "%.2f°", locationData.bearing),
        )
        StatusRow(
            label = stringResource(R.string.label_accuracy),
            value =
                String.format(
                    locale,
                    "%.2f %s",
                    displayPreferences.distanceUnit.fromMeters(locationData.accuracy.toDouble()),
                    distanceAbbr,
                ),
        )
      } else {
        Text(stringResource(R.string.label_waiting_for_location))
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
      NextSyncStatusRow(workInfo = syncWorkInfo)
    }
  }
}

@Composable
private fun NextSyncStatusRow(workInfo: WorkInfo?) {
  if (workInfo == null || workInfo.state != WorkInfo.State.ENQUEUED) return

  val target = workInfo.nextScheduleTimeMillis
  if (target == Long.MAX_VALUE) return
  val remaining = rememberCountdown(target)

  if (remaining > 1500L) {
    StatusRow(
        label = stringResource(R.string.label_next_sync),
        value = formatCountdown(remaining),
    )
  }
}
