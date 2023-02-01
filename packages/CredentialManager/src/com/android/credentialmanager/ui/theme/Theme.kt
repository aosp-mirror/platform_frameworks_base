package com.android.credentialmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

@Composable
fun CredentialSelectorTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val context = LocalContext.current

  val colorScheme =
    if (darkTheme) {
      dynamicDarkColorScheme(context)
    } else {
      dynamicLightColorScheme(context)
    }
  val androidColorScheme = AndroidColorScheme(context)
  val typography = Typography

  MaterialTheme(
    colorScheme,
    typography = typography,
    shapes = Shapes
  ) {
    CompositionLocalProvider(
      LocalAndroidColorScheme provides androidColorScheme,
    ) {
      content()
    }
  }
}
