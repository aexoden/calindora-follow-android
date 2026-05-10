package com.calindora.follow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.calindora.follow.ui.settings.SettingsScreen
import com.calindora.follow.ui.theme.AppTheme

class SettingsActivity : ComponentActivity() {
  private val viewModel: SettingsViewModel by viewModels { SettingsViewModel.factory(appContainer) }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    setContent { AppTheme { SettingsScreen(viewModel) } }
  }
}
