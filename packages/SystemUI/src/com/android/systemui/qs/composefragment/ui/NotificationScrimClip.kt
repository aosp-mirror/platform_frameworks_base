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

package com.android.systemui.qs.composefragment.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Clipping modifier for clipping out the notification scrim as it slides over QS. It will clip out
 * ([ClipOp.Difference]) a `RoundRect(-leftInset, top, width + rightInset, bottom, radius, radius)`
 * from the QS container.
 */
fun Modifier.notificationScrimClip(clipParams: () -> NotificationScrimClipParams): Modifier {
    return this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val params = clipParams()
            val left = -params.leftInset.toFloat()
            val right = size.width + params.rightInset.toFloat()
            val top = params.top.toFloat()
            val bottom = params.bottom.toFloat()
            val clipSize = Size(right - left, bottom - top)
            if (!clipSize.isEmpty()) {
                clipRect {
                    drawRoundRect(
                        color = Color.Black,
                        cornerRadius = CornerRadius(params.radius.toFloat()),
                        blendMode = BlendMode.Clear,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                    )
                }
            }
        }
}

/** Params for [notificationScrimClip]. */
data class NotificationScrimClipParams(
    val top: Int = 0,
    val bottom: Int = 0,
    val leftInset: Int = 0,
    val rightInset: Int = 0,
    val radius: Int = 0,
)
