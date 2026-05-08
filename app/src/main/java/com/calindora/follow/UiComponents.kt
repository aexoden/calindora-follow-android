package com.calindora.follow

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A standard yes/cancel confirmation dialog. */
@Composable
fun ConfirmationDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(title) },
      text = { Text(text) },
      confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
      },
  )
}
