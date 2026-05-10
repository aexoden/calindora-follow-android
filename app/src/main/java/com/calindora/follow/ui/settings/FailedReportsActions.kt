package com.calindora.follow.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.calindora.follow.R
import com.calindora.follow.ui.components.ActionButtonWithDescription
import com.calindora.follow.ui.theme.Spacing

@Composable
fun FailedReportsActions(
    failedReportCount: Int,
    onRetryClick: () -> Unit,
    onExportClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
  ActionButtonWithDescription(
      text = stringResource(R.string.action_retry_failed_reports),
      description =
          pluralStringResource(
              R.plurals.failed_reports_retry_summary,
              failedReportCount,
              failedReportCount,
          ),
      onClick = onRetryClick,
  )

  Spacer(modifier = Modifier.height(Spacing.lg))

  ActionButtonWithDescription(
      text = stringResource(R.string.action_export_failed_reports),
      description =
          pluralStringResource(
              R.plurals.failed_reports_export_summary,
              failedReportCount,
              failedReportCount,
          ),
      onClick = onExportClick,
  )

  Spacer(modifier = Modifier.height(Spacing.lg))

  ActionButtonWithDescription(
      text = stringResource(R.string.action_delete_failed_reports),
      description =
          pluralStringResource(
              R.plurals.failed_reports_delete_summary,
              failedReportCount,
              failedReportCount,
          ),
      onClick = onDeleteClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
          ),
  )
}
