package com.calindora.follow.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.calindora.follow.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.delay

/** Display formatter for wall-clock timestamps shown in the UI. */
val DISPLAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

/** Format a millisecond duration as `M:SS`. Locale-independent. */
fun formatCountdown(remainingMs: Long): String {
  val totalSeconds = (remainingMs + 999L) / 1000L
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Format a Unix-millis timestamp for display, or the localized "never" string when zero. */
@Composable
fun formatSubmissionTime(timestamp: Long): String =
    if (timestamp > 0) {
      DISPLAY_FORMATTER.format(Instant.ofEpochMilli(timestamp))
    } else {
      stringResource(R.string.label_never)
    }

/**
 * Recompose every 500 ms while [targetTime] is in the future, returning the millisecond delta from
 * now to the target (clamped at zero). Stops looping once the target passes.
 */
@Composable
fun rememberCountdown(targetTime: Long): Long {
  var now by remember(targetTime) { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(targetTime) {
    while (System.currentTimeMillis() < targetTime) {
      now = System.currentTimeMillis()
      delay(500L)
    }
    now = System.currentTimeMillis()
  }
  return (targetTime - now).coerceAtLeast(0L)
}
