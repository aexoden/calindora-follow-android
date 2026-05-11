package com.calindora.follow.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.calindora.follow.R
import com.calindora.follow.ui.components.ToggleListItem

@Composable
fun ServiceControlsSection(
    isBound: Boolean,
    isTracking: Boolean,
    isLogging: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onTrackToggle: (Boolean) -> Unit,
    onLogToggle: (Boolean) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column {
      ToggleListItem(
          headline = stringResource(R.string.controls_service),
          checked = isBound,
          onCheckedChange = onServiceToggle,
      )

      HorizontalDivider()

      ToggleListItem(
          headline = stringResource(R.string.controls_tracking),
          checked = isTracking,
          onCheckedChange = onTrackToggle,
          enabled = isBound,
      )

      ToggleListItem(
          headline = stringResource(R.string.controls_logging),
          checked = isLogging,
          onCheckedChange = onLogToggle,
          enabled = isBound,
      )
    }
  }
}
