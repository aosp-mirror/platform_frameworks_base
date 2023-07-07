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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Determinate linear progress bar. Displays the current progress of the whole process.
 *
 * Rounded corner is supported and enabled by default.
 */
@Composable
fun LinearProgressBar(
    progress: Float,
    height: Float = 4f,
    roundedCorner: Boolean = true
) {
    Box(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        val color = MaterialTheme.colorScheme.onSurface
        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        Canvas(
            Modifier
                .progressSemantics(progress)
                .fillMaxWidth()
                .height(height.dp)
        ) {
            drawLinearBarTrack(trackColor, roundedCorner)
            drawLinearBar(progress, color, roundedCorner)
        }
    }
}

private fun DrawScope.drawLinearBar(
    progress: Float,
    color: Color,
    roundedCorner: Boolean
) {
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val width = progress * size.width
    drawRoundRect(
        color = color,
        topLeft = if (isLtr) Offset.Zero else Offset((1 - progress) * size.width, 0f),
        size = Size(width, size.height),
        cornerRadius = if (roundedCorner) CornerRadius(
            size.height / 2,
            size.height / 2
        ) else CornerRadius.Zero,
    )
}

private fun DrawScope.drawLinearBarTrack(
    color: Color,
    roundedCorner: Boolean
) = drawLinearBar(1f, color, roundedCorner)

/**
 * Determinate circular progress bar. Displays the current progress of the whole process.
 *
 * Displayed in default material3 style, and rounded corner is not supported.
 */
@Composable
fun CircularProgressBar(progress: Float, radius: Float = 40f) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.size(radius.dp, radius.dp)
        )
    }
}
