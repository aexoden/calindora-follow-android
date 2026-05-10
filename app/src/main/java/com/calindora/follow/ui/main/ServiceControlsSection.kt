package com.calindora.follow.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.calindora.follow.R
import com.calindora.follow.ui.components.ToggleButton

@Composable
fun ServiceControlsSection(
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
) {
  Column {
    ToggleButton(
        checked = isBound,
        onCheckedChange = onServiceToggle,
        enabledText = stringResource(R.string.label_on),
        disabledText = stringResource(R.string.label_off),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
      ToggleButton(
          checked = isLogging,
          onCheckedChange = onLogToggle,
          enabledText = stringResource(R.string.label_logging_on),
          disabledText = stringResource(R.string.label_logging_off),
          enabled = isBound,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
      )

      ToggleButton(
          checked = isTracking,
          onCheckedChange = onTrackToggle,
          enabledText = stringResource(R.string.label_tracking_on),
          disabledText = stringResource(R.string.label_tracking_off),
          enabled = isBound,
          modifier = Modifier.weight(1f).padding(start = 8.dp),
      )
    }
  }
}
