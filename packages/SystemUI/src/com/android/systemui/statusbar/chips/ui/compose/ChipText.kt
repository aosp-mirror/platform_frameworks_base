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

package com.android.systemui.statusbar.chips.ui.compose

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.android.systemui.res.R

/**
 * Renders text within a status bar chip. The text is only displayed if more than 50% of its width
 * can fit inside the bounds of the chip. If there is any overflow,
 * [R.dimen.ongoing_activity_chip_text_fading_edge_length] is used to fade out the edge of the text.
 */
@Composable
fun ChipText(
    text: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    minimumVisibleRatio: Float = 0.5f,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val textFadeLength =
        dimensionResource(id = R.dimen.ongoing_activity_chip_text_fading_edge_length)
    val maxTextWidthDp = dimensionResource(id = R.dimen.ongoing_activity_chip_max_text_width)
    val maxTextWidthPx = with(density) { maxTextWidthDp.toPx() }

    val textLayoutResult = remember(text, style) { textMeasurer.measure(text, style) }
    val willOverflowWidth = textLayoutResult.size.width > maxTextWidthPx

    if (isSufficientlyVisible(maxTextWidthPx, minimumVisibleRatio, textLayoutResult)) {
        Text(
            text = text,
            style = style,
            softWrap = false,
            color = color,
            modifier =
                modifier
                    .sizeIn(maxWidth = maxTextWidthDp)
                    .then(
                        if (willOverflowWidth) {
                            Modifier.overflowFadeOut(
                                with(density) { textFadeLength.roundToPx() },
                                backgroundColor,
                            )
                        } else {
                            Modifier
                        }
                    ),
        )
    }
}

private fun Modifier.overflowFadeOut(fadeLength: Int, color: Color): Modifier = drawWithContent {
    drawContent()

    val brush =
        Brush.horizontalGradient(
            colors = listOf(Color.Transparent, color),
            startX = size.width - fadeLength,
            endX = size.width,
        )
    drawRect(
        brush = brush,
        topLeft = Offset(size.width - fadeLength, 0f),
        size = Size(fadeLength.toFloat(), size.height),
    )
}

/**
 * Returns `true` if at least [minimumVisibleRatio] of the text width fits within the given
 * [maxAvailableWidthPx].
 */
@Composable
private fun isSufficientlyVisible(
    maxAvailableWidthPx: Float,
    minimumVisibleRatio: Float,
    textLayoutResult: TextLayoutResult,
): Boolean {
    val widthPx = textLayoutResult.size.width

    return (maxAvailableWidthPx / widthPx) > minimumVisibleRatio
}
