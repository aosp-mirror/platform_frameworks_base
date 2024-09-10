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

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The edge of a [SceneTransitionLayout]. */
enum class Edge(private val resolveEdge: (LayoutDirection) -> Resolved) : SwipeSource {
    Top(resolveEdge = { Resolved.Top }),
    Bottom(resolveEdge = { Resolved.Bottom }),
    Left(resolveEdge = { Resolved.Left }),
    Right(resolveEdge = { Resolved.Right }),
    Start(resolveEdge = { if (it == LayoutDirection.Ltr) Resolved.Left else Resolved.Right }),
    End(resolveEdge = { if (it == LayoutDirection.Ltr) Resolved.Right else Resolved.Left });

    override fun resolve(layoutDirection: LayoutDirection): Resolved {
        return resolveEdge(layoutDirection)
    }

    enum class Resolved : SwipeSource.Resolved {
        Left,
        Right,
        Top,
        Bottom,
    }
}

val DefaultEdgeDetector = FixedSizeEdgeDetector(40.dp)

/** An [SwipeSourceDetector] that detects edges assuming a fixed edge size of [size]. */
class FixedSizeEdgeDetector(val size: Dp) : SwipeSourceDetector {
    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): Edge.Resolved? {
        val axisSize: Int
        val axisPosition: Int
        val topOrLeft: Edge.Resolved
        val bottomOrRight: Edge.Resolved
        when (orientation) {
            Orientation.Horizontal -> {
                axisSize = layoutSize.width
                axisPosition = position.x
                topOrLeft = Edge.Resolved.Left
                bottomOrRight = Edge.Resolved.Right
            }
            Orientation.Vertical -> {
                axisSize = layoutSize.height
                axisPosition = position.y
                topOrLeft = Edge.Resolved.Top
                bottomOrRight = Edge.Resolved.Bottom
            }
        }

        val sizePx = with(density) { size.toPx() }
        return when {
            axisPosition <= sizePx -> topOrLeft
            axisPosition >= axisSize - sizePx -> bottomOrRight
            else -> null
        }
    }
}
