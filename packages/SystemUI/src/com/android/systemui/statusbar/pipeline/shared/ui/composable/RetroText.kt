/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val retroColors =
    listOf(
        Color(0xFFEADFB4), // beige
        Color(0xFF9BB0C1), // gray-blue
        Color(0xFFF6995C), // orange
        Color(0xFF51829B), // cyan
    )

/** Render a single string multiple times (with offsets) kinda like retro vintage text */
@Composable
fun RetroText(text: String = "") {
    // Render the text for each retroColor, and then once for the foreground
    for (i in retroColors.size downTo 1) {
        val color = retroColors[i - 1]
        RetroTextLayer(text = text, color = color, (-1.5 * i).dp, i.dp)
    }

    RetroTextLayer(text = text, color = Color.Black, ox = 0.dp, oy = 0.dp)
}

@Composable
fun RetroTextLayer(text: String, color: Color, ox: Dp, oy: Dp) {
    Text(
        text = text,
        modifier = Modifier.offset(ox, oy),
        textAlign = TextAlign.Center,
        style =
            TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                color = color,
            ),
    )
}
