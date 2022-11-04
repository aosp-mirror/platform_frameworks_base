package com.android.credentialmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppDarkColorScheme = darkColorScheme(
  primary = Purple200,
  secondary = Purple700,
  tertiary = Teal200
)

private val AppLightColorScheme = lightColorScheme(
  primary = Purple500,
  secondary = Purple700,
  tertiary = Teal200
)

@Composable
fun CredentialSelectorTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val AppColorScheme = if (darkTheme) {
    AppDarkColorScheme
  } else {
    AppLightColorScheme
  }

  MaterialTheme(
    colorScheme = AppColorScheme,
    typography = Typography,
    shapes = Shapes,
    content = content
  )
}
