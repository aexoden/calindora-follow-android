package com.calindora.follow.ui.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * A list-style row with a label on the left and a Material 3 [Switch] on the right.
 *
 * The whole row is toggleable: tapping anywhere flips the switch.
 */
@Composable
fun ToggleListItem(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  ListItem(
      headlineContent = { Text(headline) },
      trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
      modifier =
          modifier.toggleable(
              value = checked,
              enabled = enabled,
              role = Role.Switch,
              onValueChange = onCheckedChange,
          ),
  )
}
