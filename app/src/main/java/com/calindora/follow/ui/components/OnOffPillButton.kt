package com.calindora.follow.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A pill-shaped button that visually reflects an on/off state by swapping its label and colors. */
@Composable
fun OnOffPillButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabledText: String,
    disabledText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Button(
      onClick = { onCheckedChange(!checked) },
      modifier = modifier,
      enabled = enabled,
      colors =
          ButtonDefaults.buttonColors(
              containerColor =
                  if (checked) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant,
              contentColor =
                  if (checked) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          ),
  ) {
    Text(if (checked) enabledText else disabledText)
  }
}
