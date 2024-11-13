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

package com.android.systemui.scene.ui.viewmodel

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.FixedSizeEdgeDetector
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector

/**
 * The edge of a [SceneContainer]. It differs from a standard [Edge] by splitting the top edge into
 * top-left and top-right.
 */
enum class SceneContainerEdge(private val resolveEdge: (LayoutDirection) -> Resolved) :
    SwipeSource {
    TopLeft(resolveEdge = { Resolved.TopLeft }),
    TopRight(resolveEdge = { Resolved.TopRight }),
    TopStart(
        resolveEdge = { if (it == LayoutDirection.Ltr) Resolved.TopLeft else Resolved.TopRight }
    ),
    TopEnd(
        resolveEdge = { if (it == LayoutDirection.Ltr) Resolved.TopRight else Resolved.TopLeft }
    ),
    Bottom(resolveEdge = { Resolved.Bottom }),
    Left(resolveEdge = { Resolved.Left }),
    Right(resolveEdge = { Resolved.Right }),
    Start(resolveEdge = { if (it == LayoutDirection.Ltr) Resolved.Left else Resolved.Right }),
    End(resolveEdge = { if (it == LayoutDirection.Ltr) Resolved.Right else Resolved.Left });

    override fun resolve(layoutDirection: LayoutDirection): Resolved {
        return resolveEdge(layoutDirection)
    }

    enum class Resolved : SwipeSource.Resolved {
        TopLeft,
        TopRight,
        Bottom,
        Left,
        Right,
    }
}

/**
 * A [SwipeSourceDetector] that detects edges similarly to [FixedSizeEdgeDetector], except that the
 * top edge is split in two: top-left and top-right. The split point between the two is dynamic and
 * may change during runtime.
 *
 * Callers who need to detect the start and end edges based on the layout direction (LTR vs RTL)
 * should subscribe to [SceneContainerEdge.TopStart] and [SceneContainerEdge.TopEnd] instead. These
 * will be resolved at runtime to [SceneContainerEdge.Resolved.TopLeft] and
 * [SceneContainerEdge.Resolved.TopRight] appropriately. Similarly, [SceneContainerEdge.Start] and
 * [SceneContainerEdge.End] will be resolved appropriately to [SceneContainerEdge.Resolved.Left] and
 * [SceneContainerEdge.Resolved.Right].
 *
 * @param topEdgeSplitFraction A function which returns the fraction between [0..1] (i.e.,
 *   percentage) of screen width to consider the split point between "top-left" and "top-right"
 *   edges. It is called on each source detection event.
 * @param edgeSize The fixed size of each edge.
 */
class SplitEdgeDetector(
    val topEdgeSplitFraction: () -> Float,
    val edgeSize: Dp,
) : SwipeSourceDetector {

    private val fixedEdgeDetector = FixedSizeEdgeDetector(edgeSize)

    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SceneContainerEdge.Resolved? {
        val fixedEdge =
            fixedEdgeDetector.source(
                layoutSize,
                position,
                density,
                orientation,
            )
        return when (fixedEdge) {
            Edge.Resolved.Top -> {
                val topEdgeSplitFraction = topEdgeSplitFraction()
                require(topEdgeSplitFraction in 0f..1f) {
                    "topEdgeSplitFraction must return a value between 0.0 and 1.0"
                }
                val isLeftSide = position.x < layoutSize.width * topEdgeSplitFraction
                if (isLeftSide) SceneContainerEdge.Resolved.TopLeft
                else SceneContainerEdge.Resolved.TopRight
            }
            Edge.Resolved.Left -> SceneContainerEdge.Resolved.Left
            Edge.Resolved.Bottom -> SceneContainerEdge.Resolved.Bottom
            Edge.Resolved.Right -> SceneContainerEdge.Resolved.Right
            null -> null
        }
    }
}
