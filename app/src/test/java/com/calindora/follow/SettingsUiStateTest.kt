package com.calindora.follow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {
  @Test
  fun `shouldShowResetButton is false when not blocked and below threshold`() {
    val state = SettingsUiState(isCredentialBlocked = false, consecutiveAuthFailures = 0)
    assertFalse(state.shouldShowResetButton)
  }

  @Test
  fun `shouldShowResetButton is true when credentials are blocked`() {
    val state = SettingsUiState(isCredentialBlocked = true, consecutiveAuthFailures = 0)
    assertTrue(state.shouldShowResetButton)
  }

  @Test
  fun `shouldShowResetButton is true at the failure threshold even before block flips`() {
    val state =
        SettingsUiState(
            isCredentialBlocked = false,
            consecutiveAuthFailures = Config.Submission.MAX_AUTH_FAILURES,
        )
    assertTrue(state.shouldShowResetButton)
  }

  @Test
  fun `shouldShowResetButton is false just below the failure threshold`() {
    val state =
        SettingsUiState(
            isCredentialBlocked = false,
            consecutiveAuthFailures = Config.Submission.MAX_AUTH_FAILURES - 1,
        )
    assertFalse(state.shouldShowResetButton)
  }
}
