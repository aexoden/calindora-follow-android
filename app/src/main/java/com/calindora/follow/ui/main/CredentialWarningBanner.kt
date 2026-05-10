package com.calindora.follow.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.calindora.follow.Config
import com.calindora.follow.CredentialStatus
import com.calindora.follow.R
import com.calindora.follow.ui.theme.Spacing

@Composable
fun CredentialWarningBanner(status: CredentialStatus, onClick: () -> Unit) {
  AnimatedVisibility(
      visible = status.isBlocked || status.consecutiveAuthFailures > 0,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
  ) {
    val containerColor =
        if (status.isBlocked) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor =
        if (status.isBlocked) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onTertiaryContainer
    val text =
        if (status.isBlocked) {
          stringResource(R.string.credential_warning)
        } else {
          stringResource(
              R.string.credential_warning_failures,
              status.consecutiveAuthFailures,
              Config.Submission.MAX_AUTH_FAILURES,
          )
        }

    Card(
        onClick = onClick,
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
          modifier =
              Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Icon(
            painter = painterResource(R.drawable.chevron_right_24px),
            contentDescription = null,
            tint = contentColor,
        )
      }
    }
  }
}
