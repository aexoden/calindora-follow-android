package com.calindora.follow.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.work.WorkInfo
import com.calindora.follow.R

@Composable
fun WorkInfo.State.toLabel(): String =
    stringResource(
        when (this) {
          WorkInfo.State.ENQUEUED -> R.string.sync_state_enqueued
          WorkInfo.State.RUNNING -> R.string.sync_state_running
          WorkInfo.State.SUCCEEDED -> R.string.sync_state_succeeded
          WorkInfo.State.FAILED -> R.string.sync_state_failed
          WorkInfo.State.BLOCKED -> R.string.sync_state_blocked
          WorkInfo.State.CANCELLED -> R.string.sync_state_cancelled
        }
    )

@Composable
fun stopReasonLabel(stopReason: Int): String =
    when (stopReason) {
      WorkInfo.STOP_REASON_CANCELLED_BY_APP -> stringResource(R.string.stop_reason_cancelled_by_app)
      WorkInfo.STOP_REASON_PREEMPT -> stringResource(R.string.stop_reason_preempt)
      WorkInfo.STOP_REASON_TIMEOUT -> stringResource(R.string.stop_reason_timeout)
      WorkInfo.STOP_REASON_DEVICE_STATE -> stringResource(R.string.stop_reason_device_state)
      WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW ->
          stringResource(R.string.stop_reason_battery_not_low)
      WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> stringResource(R.string.stop_reason_charging)
      WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY ->
          stringResource(R.string.stop_reason_connectivity)
      WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE ->
          stringResource(R.string.stop_reason_device_idle)
      WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW ->
          stringResource(R.string.stop_reason_storage_not_low)
      WorkInfo.STOP_REASON_QUOTA -> stringResource(R.string.stop_reason_quota)
      WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION ->
          stringResource(R.string.stop_reason_background_restriction)
      WorkInfo.STOP_REASON_APP_STANDBY -> stringResource(R.string.stop_reason_app_standby)
      WorkInfo.STOP_REASON_USER -> stringResource(R.string.stop_reason_user)
      WorkInfo.STOP_REASON_SYSTEM_PROCESSING ->
          stringResource(R.string.stop_reason_system_processing)
      WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED ->
          stringResource(R.string.stop_reason_estimated_app_launch_time_changed)
      WorkInfo.STOP_REASON_UNKNOWN -> stringResource(R.string.stop_reason_unknown)
      else -> stringResource(R.string.stop_reason_other, stopReason)
    }
