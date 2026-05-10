package com.calindora.follow.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.calindora.follow.R
import com.calindora.follow.ui.components.OnOffPillButton
import com.calindora.follow.ui.theme.Spacing

@Composable
fun ServiceControlsSection(
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
    OnOffPillButton(
        checked = isBound,
        onCheckedChange = onServiceToggle,
        enabledText = stringResource(R.string.label_on),
        disabledText = stringResource(R.string.label_off),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
      OnOffPillButton(
          checked = isLogging,
          onCheckedChange = onLogToggle,
          enabledText = stringResource(R.string.label_logging_on),
          disabledText = stringResource(R.string.label_logging_off),
          enabled = isBound,
          modifier = Modifier.weight(1f),
      )

      OnOffPillButton(
          checked = isTracking,
          onCheckedChange = onTrackToggle,
          enabledText = stringResource(R.string.label_tracking_on),
          disabledText = stringResource(R.string.label_tracking_off),
          enabled = isBound,
          modifier = Modifier.weight(1f),
      )
    }
  }
}
