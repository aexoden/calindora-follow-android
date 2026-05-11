package com.calindora.follow.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.calindora.follow.R
import com.calindora.follow.SubmissionWorker
import com.calindora.follow.ui.components.ConfirmationDialog
import com.calindora.follow.ui.components.StatusRow
import com.calindora.follow.ui.components.ToggleListItem
import com.calindora.follow.ui.format.formatCountdown
import com.calindora.follow.ui.format.rememberCountdown
import com.calindora.follow.ui.format.stopReasonLabel
import com.calindora.follow.ui.format.toLabel
import com.calindora.follow.ui.theme.Spacing

@Composable
fun DebugSection(
    isDebugEnabled: Boolean,
    onDebugToggle: (Boolean) -> Unit,
    syncWorkInfo: WorkInfo?,
    queueSize: Int,
    onClearClick: () -> Unit,
    onDropFirstClick: () -> Unit,
    onForceSyncClick: () -> Unit,
) {
  var showClearConfirm by remember { mutableStateOf(false) }
  var showDropFirstConfirm by remember { mutableStateOf(false) }

  Column {
    Card(modifier = Modifier.fillMaxWidth()) {
      ToggleListItem(
          headline = stringResource(R.string.controls_debug),
          checked = isDebugEnabled,
          onCheckedChange = onDebugToggle,
      )
    }

    AnimatedVisibility(
        visible = isDebugEnabled,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
      Column(
          modifier = Modifier.padding(top = Spacing.lg),
          verticalArrangement = Arrangement.spacedBy(Spacing.lg),
      ) {
        SyncStatusCard(workInfo = syncWorkInfo)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
          Button(
              onClick = { showClearConfirm = true },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = MaterialTheme.colorScheme.error,
                      contentColor = MaterialTheme.colorScheme.onError,
                  ),
              modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.label_clear_queue))
          }

          Button(
              onClick = { showDropFirstConfirm = true },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = MaterialTheme.colorScheme.error,
                      contentColor = MaterialTheme.colorScheme.onError,
                  ),
              modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.label_drop_first_queued))
          }
        }

        Button(onClick = onForceSyncClick, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.label_force_sync))
        }
      }
    }
  }

  if (showClearConfirm) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_clear_queue_title),
        text = pluralStringResource(R.plurals.dialog_clear_queue_message, queueSize, queueSize),
        confirmText = stringResource(R.string.action_clear),
        onConfirm = {
          showClearConfirm = !showClearConfirm
          onClearClick()
        },
        onDismiss = { showClearConfirm = !showClearConfirm },
    )
  }

  if (showDropFirstConfirm) {
    ConfirmationDialog(
        title = stringResource(R.string.dialog_drop_first_title),
        text = stringResource(R.string.dialog_drop_first_message),
        confirmText = stringResource(R.string.action_drop),
        onConfirm = {
          showDropFirstConfirm = !showDropFirstConfirm
          onDropFirstClick()
        },
        onDismiss = { showDropFirstConfirm = !showDropFirstConfirm },
    )
  }
}

@Composable
private fun SyncStatusCard(workInfo: WorkInfo?) {
  Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          text = stringResource(R.string.sync_status_card_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      if (workInfo == null) {
        Text(
            text = stringResource(R.string.sync_status_no_work),
            style = MaterialTheme.typography.bodyMedium,
        )
        return@Card
      }

      StatusRow(label = stringResource(R.string.label_sync_state), value = workInfo.state.toLabel())
      StatusRow(
          label = stringResource(R.string.label_sync_attempts),
          value = workInfo.runAttemptCount.toString(),
      )

      if (workInfo.state == WorkInfo.State.ENQUEUED) {
        val target = workInfo.nextScheduleTimeMillis
        if (target != Long.MAX_VALUE) {
          val remaining = rememberCountdown(target)
          StatusRow(
              label = stringResource(R.string.label_next_sync),
              value = if (remaining > 0) formatCountdown(remaining) else "-",
          )
        }
      }

      if (workInfo.state == WorkInfo.State.FAILED) {
        val errorReason = workInfo.outputData.getString(SubmissionWorker.OUTPUT_KEY_ERROR_REASON)
        if (!errorReason.isNullOrEmpty()) {
          StatusRow(label = stringResource(R.string.label_sync_last_error), value = errorReason)
        }
      }

      val stopReason = workInfo.stopReason
      if (stopReason != WorkInfo.STOP_REASON_NOT_STOPPED) {
        StatusRow(
            label = stringResource(R.string.label_sync_stop_reason),
            value = stopReasonLabel(stopReason),
        )
      }
    }
  }
}
