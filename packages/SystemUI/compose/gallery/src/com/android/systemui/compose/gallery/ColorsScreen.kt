/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.compose.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.systemui.compose.theme.LocalAndroidColorScheme

/** The screen that shows all the Material 3 colors. */
@Composable
fun MaterialColorsScreen() {
    val colors = MaterialTheme.colorScheme
    ColorsScreen(
        listOf(
            "primary" to colors.primary,
            "onPrimary" to colors.onPrimary,
            "primaryContainer" to colors.primaryContainer,
            "onPrimaryContainer" to colors.onPrimaryContainer,
            "inversePrimary" to colors.inversePrimary,
            "secondary" to colors.secondary,
            "onSecondary" to colors.onSecondary,
            "secondaryContainer" to colors.secondaryContainer,
            "onSecondaryContainer" to colors.onSecondaryContainer,
            "tertiary" to colors.tertiary,
            "onTertiary" to colors.onTertiary,
            "tertiaryContainer" to colors.tertiaryContainer,
            "onTertiaryContainer" to colors.onTertiaryContainer,
            "background" to colors.background,
            "onBackground" to colors.onBackground,
            "surface" to colors.surface,
            "onSurface" to colors.onSurface,
            "surfaceVariant" to colors.surfaceVariant,
            "onSurfaceVariant" to colors.onSurfaceVariant,
            "inverseSurface" to colors.inverseSurface,
            "inverseOnSurface" to colors.inverseOnSurface,
            "error" to colors.error,
            "onError" to colors.onError,
            "errorContainer" to colors.errorContainer,
            "onErrorContainer" to colors.onErrorContainer,
            "outline" to colors.outline,
        )
    )
}

/** The screen that shows all the Android colors. */
@Composable
fun AndroidColorsScreen() {
    val colors = LocalAndroidColorScheme.current
    ColorsScreen(
        listOf(
            "colorPrimary" to colors.colorPrimary,
            "colorPrimaryDark" to colors.colorPrimaryDark,
            "colorAccent" to colors.colorAccent,
            "colorAccentPrimary" to colors.colorAccentPrimary,
            "colorAccentSecondary" to colors.colorAccentSecondary,
            "colorAccentTertiary" to colors.colorAccentTertiary,
            "colorAccentPrimaryVariant" to colors.colorAccentPrimaryVariant,
            "colorAccentSecondaryVariant" to colors.colorAccentSecondaryVariant,
            "colorAccentTertiaryVariant" to colors.colorAccentTertiaryVariant,
            "colorSurface" to colors.colorSurface,
            "colorSurfaceHighlight" to colors.colorSurfaceHighlight,
            "colorSurfaceVariant" to colors.colorSurfaceVariant,
            "colorSurfaceHeader" to colors.colorSurfaceHeader,
            "colorError" to colors.colorError,
            "colorBackground" to colors.colorBackground,
            "colorBackgroundFloating" to colors.colorBackgroundFloating,
            "panelColorBackground" to colors.panelColorBackground,
            "textColorPrimary" to colors.textColorPrimary,
            "textColorSecondary" to colors.textColorSecondary,
            "textColorTertiary" to colors.textColorTertiary,
            "textColorPrimaryInverse" to colors.textColorPrimaryInverse,
            "textColorSecondaryInverse" to colors.textColorSecondaryInverse,
            "textColorTertiaryInverse" to colors.textColorTertiaryInverse,
            "textColorOnAccent" to colors.textColorOnAccent,
            "colorForeground" to colors.colorForeground,
            "colorForegroundInverse" to colors.colorForegroundInverse,
        )
    )
}

@Composable
private fun ColorsScreen(
    colors: List<Pair<String, Color>>,
) {
    LazyColumn(
        Modifier.fillMaxWidth(),
    ) {
        colors.forEach { (name, color) -> item { ColorTile(color, name) } }
    }
}

@Composable
private fun ColorTile(
    color: Color,
    name: String,
) {
    Row(
        Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Spacer(
            Modifier.border(1.dp, MaterialTheme.colorScheme.onBackground, shape)
                .background(color, shape)
                .size(64.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(name)
    }
}
