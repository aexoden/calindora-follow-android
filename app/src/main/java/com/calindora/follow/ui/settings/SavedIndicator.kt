package com.calindora.follow.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.calindora.follow.R
import com.calindora.follow.ui.theme.Spacing

@Composable
fun SavedIndicator(visible: Boolean) {
  AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.padding(end = Spacing.md),
    ) {
      Icon(
          painter = painterResource(R.drawable.check_24px),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
      )
      Text(
          text = stringResource(R.string.indicator_saved),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
