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

package com.android.compose.animation.scene

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import com.android.compose.animation.scene.transformation.ModifierTransformation

/** Punch a hole in an element using the bounds of another element and a given [shape]. */
internal class PunchHole(
    override val matcher: ElementMatcher,
    private val bounds: ElementKey,
    private val shape: Shape,
) : ModifierTransformation {

    private var lastSize: Size = Size.Unspecified
    private var lastLayoutDirection: LayoutDirection = LayoutDirection.Ltr
    private var lastOutline: Outline? = null

    override fun Modifier.transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: Scene,
        element: Element,
        sceneValues: Element.TargetValues,
    ): Modifier {
        return drawWithContent {
            val bounds = layoutImpl.elements[bounds]
            if (
                bounds == null ||
                    bounds.lastSharedValues.size == Element.SizeUnspecified ||
                    bounds.lastSharedValues.offset == Offset.Unspecified
            ) {
                drawContent()
                return@drawWithContent
            }
            drawIntoCanvas { canvas ->
                canvas.withSaveLayer(size.toRect(), Paint()) {
                    drawContent()

                    val offset = bounds.lastSharedValues.offset - element.lastSharedValues.offset
                    translate(offset.x, offset.y) { drawHole(bounds) }
                }
            }
        }
    }

    private fun DrawScope.drawHole(bounds: Element) {
        val boundsSize = bounds.lastSharedValues.size.toSize()
        if (shape == RectangleShape) {
            drawRect(Color.Black, size = boundsSize, blendMode = BlendMode.DstOut)
            return
        }

        val outline =
            if (boundsSize == lastSize && layoutDirection == lastLayoutDirection) {
                lastOutline!!
            } else {
                val newOutline = shape.createOutline(boundsSize, layoutDirection, this)
                lastSize = boundsSize
                lastLayoutDirection = layoutDirection
                lastOutline = newOutline
                newOutline
            }

        drawOutline(
            outline,
            Color.Black,
            blendMode = BlendMode.DstOut,
        )
    }
}
