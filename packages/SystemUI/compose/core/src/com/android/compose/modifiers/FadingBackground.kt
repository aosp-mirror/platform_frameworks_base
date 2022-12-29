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

package com.android.compose.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.InspectorValueInfo
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.LayoutDirection

/**
 * Draws a fading [shape] with a solid [color] and [alpha] behind the content.
 *
 * @param color color to paint background with
 * @param alpha alpha of the background
 * @param shape desired shape of the background
 */
fun Modifier.background(
    color: Color,
    alpha: () -> Float,
    shape: Shape = RectangleShape,
) =
    this.then(
        FadingBackground(
            brush = SolidColor(color),
            alpha = alpha,
            shape = shape,
            inspectorInfo =
                debugInspectorInfo {
                    name = "background"
                    value = color
                    properties["color"] = color
                    properties["alpha"] = alpha
                    properties["shape"] = shape
                }
        )
    )

private class FadingBackground
constructor(
    private val brush: Brush,
    private val shape: Shape,
    private val alpha: () -> Float,
    inspectorInfo: InspectorInfo.() -> Unit
) : DrawModifier, InspectorValueInfo(inspectorInfo) {
    // naive cache outline calculation if size is the same
    private var lastSize: Size? = null
    private var lastLayoutDirection: LayoutDirection? = null
    private var lastOutline: Outline? = null

    override fun ContentDrawScope.draw() {
        if (shape === RectangleShape) {
            // shortcut to avoid Outline calculation and allocation
            drawRect()
        } else {
            drawOutline()
        }
        drawContent()
    }

    private fun ContentDrawScope.drawRect() {
        drawRect(brush, alpha = alpha())
    }

    private fun ContentDrawScope.drawOutline() {
        val outline =
            if (size == lastSize && layoutDirection == lastLayoutDirection) {
                lastOutline!!
            } else {
                shape.createOutline(size, layoutDirection, this)
            }
        drawOutline(outline, brush = brush, alpha = alpha())
        lastOutline = outline
        lastSize = size
        lastLayoutDirection = layoutDirection
    }

    override fun hashCode(): Int {
        var result = brush.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + shape.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        val otherModifier = other as? FadingBackground ?: return false
        return brush == otherModifier.brush &&
            alpha == otherModifier.alpha &&
            shape == otherModifier.shape
    }

    override fun toString(): String = "FadingBackground(brush=$brush, alpha = $alpha, shape=$shape)"
}
