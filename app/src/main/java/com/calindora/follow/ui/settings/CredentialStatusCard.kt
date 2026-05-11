package com.calindora.follow.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.calindora.follow.Config
import com.calindora.follow.R
import com.calindora.follow.ui.theme.Spacing

@Composable
fun CredentialStatusCard(isBlocked: Boolean, consecutiveAuthFailures: Int) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(Spacing.lg)) {
      val (text, color) =
          when {
            isBlocked ->
                stringResource(R.string.preference_credential_status_blocked) to
                    MaterialTheme.colorScheme.error
            consecutiveAuthFailures > 0 ->
                pluralStringResource(
                    R.plurals.preference_credential_status_failures,
                    consecutiveAuthFailures,
                    consecutiveAuthFailures,
                    Config.Submission.MAX_AUTH_FAILURES,
                ) to MaterialTheme.colorScheme.onSurfaceVariant
            else ->
                stringResource(R.string.preference_credential_status_ok) to
                    MaterialTheme.colorScheme.onSurface
          }

      Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
  }
}
