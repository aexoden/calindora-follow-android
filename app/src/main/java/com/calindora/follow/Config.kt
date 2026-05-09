package com.calindora.follow

import java.util.concurrent.TimeUnit

/**
 * Compile-time tuning constants for Calindora Follow.
 *
 * These constants are used to configure the behavior of the app. Some of them may be candidates for
 * promotion to user-configurable settings in the future, but for now they are hardcoded here.
 *
 * For user-configurable settings, see [AppPreferences].
 */
object Config {
  /** GPS sampling and submission queueing. */
  object Tracking {
    /**
     * Minimum interval between location reports queued for submission, in milliseconds. Lower
     * values capture finer movement at the cost of battery and queue pressure.
     */
    const val UPDATE_INTERVAL_MS = 5_000L
  }

  /** HTTP client configuration for [FollowApi]. */
  object Network {
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 10L
  }

  /** Submission worker batching and retry behavior. */
  object Submission {
    /** Maximum number of unsubmitted reports loaded per batch fetch. */
    const val MAX_BATCH_SIZE = 50

    /** Maximum submission attempts per report before it is marked permanently failed. */
    const val MAX_ATTEMPTS = 5

    /**
     * Maximum number of consecutive HTTP 401 responses before submissions are blocked entirely and
     * the user is notified to fix their credentials.
     */
    const val MAX_AUTH_FAILURES = 3

    /** Linear backoff delay between successive submission work failures. */
    const val BACKOFF_DELAY_MS = 30_000L
  }

  /** Database retention. */
  object Retention {
    /**
     * How long successfully submitted reports are retained for diagnostic purposes before being
     * deleted by the next submission run.
     */
    val SUBMITTED_REPORT_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)
  }

  /** Settings screen UI timing. */
  object Ui {
    /** Debounce window for persisting text-field edits to DataStore. */
    const val SAVE_DEBOUNCE_MS = 500L

    /** How long the "Saved" indicator remains visible after a debounced save fires. */
    const val SAVED_INDICATOR_VISIBLE_MS = 1_500L
  }
}
