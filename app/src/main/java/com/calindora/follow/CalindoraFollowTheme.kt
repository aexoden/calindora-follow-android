package com.calindora.follow

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF00658E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC7E7FF),
        onPrimaryContainer = Color(0xFF001E2E),
        secondary = Color(0xFF50606F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD3E5F6),
        onSecondaryContainer = Color(0xFF0B1D2A),
        tertiary = Color(0xFF66587A),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFECDCFF),
        onTertiaryContainer = Color(0xFF211634),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFCFCFF),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFFCFCFF),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFDDE3EB),
        onSurfaceVariant = Color(0xFF41484D),
        outline = Color(0xFF71787E),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF87CDFF),
        onPrimary = Color(0xFF00344C),
        primaryContainer = Color(0xFF004C6C),
        onPrimaryContainer = Color(0xFFC7E7FF),
        secondary = Color(0xFFB7C9DA),
        onSecondary = Color(0xFF223240),
        secondaryContainer = Color(0xFF394857),
        onSecondaryContainer = Color(0xFFD3E5F6),
        tertiary = Color(0xFFD0BFE7),
        onTertiary = Color(0xFF362B49),
        tertiaryContainer = Color(0xFF4D4161),
        onTertiaryContainer = Color(0xFFECDCFF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF191C1E),
        onBackground = Color(0xFFE1E2E5),
        surface = Color(0xFF191C1E),
        onSurface = Color(0xFFE1E2E5),
        surfaceVariant = Color(0xFF41484D),
        onSurfaceVariant = Color(0xFFC1C7CE),
        outline = Color(0xFF8B9198),
    )

@Composable
fun CalindoraFollowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
  val colorScheme =
      when {
        dynamicColor -> {
          val context = LocalContext.current
          if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
      }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
