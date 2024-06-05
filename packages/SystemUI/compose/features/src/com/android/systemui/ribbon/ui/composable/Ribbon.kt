/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.ribbon.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import com.android.compose.modifiers.thenIf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Renders a "ribbon" at the bottom right corner of its container.
 *
 * The [content] is rendered leaning at an angle of [degrees] degrees (between `1` and `89`,
 * inclusive), with an alpha of [alpha] (between `0f` and `1f`, inclusive).
 *
 * The background color of the strip can be modified by passing a value to the [backgroundColor] or
 * `null` to remove the strip background.
 *
 * Note: this function assumes that it's been placed at the bottom right of its parent by its
 * caller. It's the caller's responsibility to meet that assumption by actually placing this
 * composable element at the bottom right.
 */
@Composable
fun BottomRightCornerRibbon(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    degrees: Int = 45,
    alpha: Float = 0.6f,
    backgroundColor: Color? = Color.Red,
) {
    check(degrees in 1..89)
    check(alpha in 0f..1f)

    val radians = degrees * (PI / 180)

    Box(
        content = { content() },
        modifier =
            modifier
                .graphicsLayer {
                    this.alpha = alpha

                    val w = size.width
                    val h = size.height

                    val sine = sin(radians).toFloat()
                    val cosine = cos(radians).toFloat()

                    translationX = (w - w * cosine + h * sine) / 2f
                    translationY = (h - w * sine + h * cosine) / 2f
                    rotationZ = 360f - degrees
                }
                .thenIf(backgroundColor != null) { Modifier.background(backgroundColor!!) }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)

                    val tangent = tan(radians)
                    val leftPadding = (placeable.measuredHeight / tangent).roundToInt()
                    val rightPadding = (placeable.measuredHeight * tangent).roundToInt()

                    layout(
                        width = placeable.measuredWidth + leftPadding + rightPadding,
                        height = placeable.measuredHeight,
                    ) {
                        placeable.place(leftPadding, 0)
                    }
                }
    )
}
