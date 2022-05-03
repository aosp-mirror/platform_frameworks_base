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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** The screen that shows the Material text styles. */
@Composable
fun TypographyScreen() {
    val typography = MaterialTheme.typography

    Column(
        Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState()),
    ) {
        FontLine("displayLarge", typography.displayLarge)
        FontLine("displayMedium", typography.displayMedium)
        FontLine("displaySmall", typography.displaySmall)
        FontLine("headlineLarge", typography.headlineLarge)
        FontLine("headlineMedium", typography.headlineMedium)
        FontLine("headlineSmall", typography.headlineSmall)
        FontLine("titleLarge", typography.titleLarge)
        FontLine("titleMedium", typography.titleMedium)
        FontLine("titleSmall", typography.titleSmall)
        FontLine("bodyLarge", typography.bodyLarge)
        FontLine("bodyMedium", typography.bodyMedium)
        FontLine("bodySmall", typography.bodySmall)
        FontLine("labelLarge", typography.labelLarge)
        FontLine("labelMedium", typography.labelMedium)
        FontLine("labelSmall", typography.labelSmall)
    }
}

@Composable
private fun FontLine(name: String, style: TextStyle) {
    Text(
        "$name (${style.fontSize}/${style.lineHeight}, W${style.fontWeight?.weight})",
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Visible,
    )
}
