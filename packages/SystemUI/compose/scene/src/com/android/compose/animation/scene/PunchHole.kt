/*
 * Copyright (C) 2023 The Android Open Source Project
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

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize

/**
 * Punch a hole in this node with the given [size], [offset] and [shape].
 *
 * Punching a hole in an element will "remove" any pixel drawn by that element in the hole area.
 * This can be used to make content drawn below an opaque element visible. For example, if we have
 * [this lockscreen scene](http://shortn/_VYySFnJDhN) drawn below
 * [this shade scene](http://shortn/_fpxGUk0Rg7) and punch a hole in the latter using the big clock
 * time bounds and a RoundedCornerShape(10dp), [this](http://shortn/_qt80IvORFj) would be the
 * result.
 */
@Stable
fun Modifier.punchHole(
    size: () -> Size,
    offset: () -> Offset,
    shape: Shape = RectangleShape,
): Modifier = this.then(PunchHoleElement(size, offset, shape))

/**
 * Punch a hole in this node using the bounds of [coords] and the given [shape].
 *
 * You can use [androidx.compose.ui.layout.onGloballyPositioned] to get the last coordinates of a
 * node.
 */
@Stable
fun Modifier.punchHole(
    coords: () -> LayoutCoordinates?,
    shape: Shape = RectangleShape,
): Modifier = this.then(PunchHoleWithBoundsElement(coords, shape))

private data class PunchHoleElement(
    private val size: () -> Size,
    private val offset: () -> Offset,
    private val shape: Shape,
) : ModifierNodeElement<PunchHoleNode>() {
    override fun create(): PunchHoleNode = PunchHoleNode(size, offset, { shape })

    override fun update(node: PunchHoleNode) {
        node.size = size
        node.offset = offset
        node.shape = { shape }
    }
}

private class PunchHoleNode(
    var size: () -> Size,
    var offset: () -> Offset,
    var shape: () -> Shape,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private var lastSize: Size = Size.Unspecified
    private var lastLayoutDirection: LayoutDirection = LayoutDirection.Ltr
    private var lastOutline: Outline? = null

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        return measurable.measure(constraints).run {
            layout(width, height) {
                placeWithLayer(0, 0) { compositingStrategy = CompositingStrategy.Offscreen }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()

        val holeSize = size()
        if (holeSize != Size.Zero) {
            val offset = offset()
            translate(offset.x, offset.y) { drawHole(holeSize) }
        }
    }

    private fun DrawScope.drawHole(size: Size) {
        if (shape == RectangleShape) {
            drawRect(Color.Black, size = size, blendMode = BlendMode.DstOut)
            return
        }

        val outline =
            if (size == lastSize && layoutDirection == lastLayoutDirection) {
                lastOutline!!
            } else {
                val newOutline = shape().createOutline(size, layoutDirection, this)
                lastSize = size
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

private data class PunchHoleWithBoundsElement(
    private val coords: () -> LayoutCoordinates?,
    private val shape: Shape,
) : ModifierNodeElement<PunchHoleWithBoundsNode>() {
    override fun create(): PunchHoleWithBoundsNode = PunchHoleWithBoundsNode(coords, shape)

    override fun update(node: PunchHoleWithBoundsNode) {
        node.holeCoords = coords
        node.shape = shape
    }
}

private class PunchHoleWithBoundsNode(
    var holeCoords: () -> LayoutCoordinates?,
    var shape: Shape,
) : DelegatingNode(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private val delegate = delegate(PunchHoleNode(::holeSize, ::holeOffset, ::shape))
    private var lastCoords: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.lastCoords = coordinates
    }

    override fun ContentDrawScope.draw() = with(delegate) { draw() }

    private fun holeSize(): Size {
        return holeCoords()?.size?.toSize() ?: Size.Zero
    }

    private fun holeOffset(): Offset {
        val holeCoords = holeCoords() ?: return Offset.Zero
        val lastCoords = lastCoords ?: error("draw() was called before onGloballyPositioned()")
        return lastCoords.localPositionOf(holeCoords, relativeToSource = Offset.Zero)
    }
}
