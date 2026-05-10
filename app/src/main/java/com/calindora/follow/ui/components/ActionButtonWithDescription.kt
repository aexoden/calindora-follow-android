package com.calindora.follow.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.calindora.follow.ui.theme.Spacing

@Composable
fun ActionButtonWithDescription(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { Text(text) }
    Spacer(modifier = Modifier.height(Spacing.sm))
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}
