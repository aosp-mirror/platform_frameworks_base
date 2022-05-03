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

/** The screen that shows all the Material 3 colors. */
@Composable
fun ColorsScreen() {
    val colors = MaterialTheme.colorScheme
    LazyColumn {
        item { ColorTile(colors.primary, "primary") }
        item { ColorTile(colors.onPrimary, "onPrimary") }
        item { ColorTile(colors.primaryContainer, "primaryContainer") }
        item { ColorTile(colors.onPrimaryContainer, "onPrimaryContainer") }
        item { ColorTile(colors.inversePrimary, "inversePrimary") }
        item { ColorTile(colors.secondary, "secondary") }
        item { ColorTile(colors.onSecondary, "onSecondary") }
        item { ColorTile(colors.secondaryContainer, "secondaryContainer") }
        item { ColorTile(colors.onSecondaryContainer, "onSecondaryContainer") }
        item { ColorTile(colors.tertiary, "tertiary") }
        item { ColorTile(colors.onTertiary, "onTertiary") }
        item { ColorTile(colors.tertiaryContainer, "tertiaryContainer") }
        item { ColorTile(colors.onTertiaryContainer, "onTertiaryContainer") }
        item { ColorTile(colors.background, "background") }
        item { ColorTile(colors.onBackground, "onBackground") }
        item { ColorTile(colors.surface, "surface") }
        item { ColorTile(colors.onSurface, "onSurface") }
        item { ColorTile(colors.surfaceVariant, "surfaceVariant") }
        item { ColorTile(colors.onSurfaceVariant, "onSurfaceVariant") }
        item { ColorTile(colors.inverseSurface, "inverseSurface") }
        item { ColorTile(colors.inverseOnSurface, "inverseOnSurface") }
        item { ColorTile(colors.error, "error") }
        item { ColorTile(colors.onError, "onError") }
        item { ColorTile(colors.errorContainer, "errorContainer") }
        item { ColorTile(colors.onErrorContainer, "onErrorContainer") }
        item { ColorTile(colors.outline, "outline") }
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
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.onBackground, shape)
                .background(color, shape)
                .size(64.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(name)
    }
}