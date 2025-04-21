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

val Purple200 = Color(0xFFBB86FC)
val Purple500 = Color(0xFF6200EE)
val Purple700 = Color(0xFF3700B3)
val Teal200 = Color(0xFF03DAC5)
val Teal700 = Color(0xFF018786)

private val LightColors =
    lightColorScheme(
        primary = Purple500,
        onPrimary = Color.White,
        primaryContainer = Purple200,
        onPrimaryContainer = Color.Black,
        secondary = Teal200,
        onSecondary = Color.Black,
        secondaryContainer = Teal700,
        onSecondaryContainer = Color.White,
    )

private val DarkColors =
    darkColorScheme(
        primary = Purple200,
        onPrimary = Color.Black,
        primaryContainer = Purple700,
        onPrimaryContainer = Color.White,
        secondary = Teal200,
        onSecondary = Color.Black,
        secondaryContainer = Teal200,
        onSecondaryContainer = Color.Black,
    )

@Composable
fun CalindoraFollowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
