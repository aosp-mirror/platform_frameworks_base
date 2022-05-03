package com.android.systemui.compose.gallery

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.filled.FormatTextdirectionRToL
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

enum class FontScale(val scale: Float) {
    Small(0.85f),
    Normal(1f),
    Big(1.15f),
    Bigger(1.30f),
}

/** A configuration panel that allows to toggle the theme, font scale and layout direction. */
@Composable
fun ConfigurationControls(
    isDarkTheme: Boolean,
    fontScale: FontScale,
    layoutDirection: LayoutDirection,
    onChangeTheme: () -> Unit,
    onChangeLayoutDirection: () -> Unit,
    onChangeFontScale: () -> Unit,
) {
    LazyRow {
        // Dark/light theme.
        item {
            TextButton(onChangeTheme) {
                val text: String
                val icon: ImageVector
                if (isDarkTheme) {
                    icon = Icons.Default.BrightnessHigh
                    text = "Dark"
                } else {
                    icon = Icons.Default.BrightnessLow
                    text = "Light"
                }

                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
                Text(text)
            }
        }

        // Font scale.
        item {
            TextButton(onChangeFontScale) {
                Icon(Icons.Default.FormatSize, null)
                Spacer(Modifier.width(8.dp))

                Text(fontScale.name)
            }
        }

        // Layout direction.
        item {
            TextButton(onChangeLayoutDirection) {
                when (layoutDirection) {
                    LayoutDirection.Ltr -> {
                        Icon(Icons.Default.FormatTextdirectionLToR, null)
                        Spacer(Modifier.width(8.dp))
                        Text("LTR")
                    }
                    LayoutDirection.Rtl -> {
                        Icon(Icons.Default.FormatTextdirectionRToL, null)
                        Spacer(Modifier.width(8.dp))
                        Text("RTL")
                    }
                }
            }
        }
    }
}