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

package com.android.compose.animation

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/** A component that can bounce in one dimension, for instance when it is tapped. */
interface Bounceable {
    val bounce: Dp
}

/**
 * Bounce a composable in the given [orientation] when this [bounceable], the [previousBounceable]
 * or [nextBounceable] is bouncing.
 *
 * Important: This modifier should be used on composables that have a fixed size in [orientation],
 * i.e. they should be placed *after* modifiers like Modifier.fillMaxWidth() or Modifier.height().
 *
 * @param bounceable the [Bounceable] associated to the current composable that will make this
 *   composable size grow when bouncing.
 * @param previousBounceable the [Bounceable] associated to the previous composable in [orientation]
 *   that will make this composable shrink when bouncing.
 * @param nextBounceable the [Bounceable] associated to the next composable in [orientation] that
 *   will make this composable shrink when bouncing.
 * @param orientation the orientation in which this bounceable should grow/shrink.
 * @param bounceEnd whether this bounceable should bounce on the end (right in LTR layouts, left in
 *   RTL layouts) side. This can be used for grids for which the last item does not align perfectly
 *   with the end of the grid.
 */
fun Modifier.bounceable(
    bounceable: Bounceable,
    previousBounceable: Bounceable?,
    nextBounceable: Bounceable?,
    orientation: Orientation,
    bounceEnd: Boolean = nextBounceable != null,
): Modifier {
    return layout { measurable, constraints ->
        // The constraints in the orientation should be fixed, otherwise there is no way to know
        // what the size of our child node will be without this animation code.
        checkFixedSize(constraints, orientation)

        var sizePrevious = 0f
        var sizeNext = 0f

        if (previousBounceable != null) {
            sizePrevious += bounceable.bounce.toPx() - previousBounceable.bounce.toPx()
        }

        if (nextBounceable != null) {
            sizeNext += bounceable.bounce.toPx() - nextBounceable.bounce.toPx()
        } else if (bounceEnd) {
            sizeNext += bounceable.bounce.toPx()
        }

        when (orientation) {
            Orientation.Horizontal -> {
                val idleWidth = constraints.maxWidth
                val animatedWidth = (idleWidth + sizePrevious + sizeNext).roundToInt()
                val animatedConstraints =
                    constraints.copy(minWidth = animatedWidth, maxWidth = animatedWidth)

                val placeable = measurable.measure(animatedConstraints)

                // Important: we still place the element using the idle size coming from the
                // constraints, otherwise the parent will automatically center this node given the
                // size that it expects us to be. This allows us to then place the element where we
                // want it to be.
                layout(idleWidth, placeable.height) {
                    placeable.placeRelative(-sizePrevious.roundToInt(), 0)
                }
            }
            Orientation.Vertical -> {
                val idleHeight = constraints.maxHeight
                val animatedHeight = (idleHeight + sizePrevious + sizeNext).roundToInt()
                val animatedConstraints =
                    constraints.copy(minHeight = animatedHeight, maxHeight = animatedHeight)

                val placeable = measurable.measure(animatedConstraints)
                layout(placeable.width, idleHeight) {
                    placeable.placeRelative(0, -sizePrevious.roundToInt())
                }
            }
        }
    }
}

private fun checkFixedSize(constraints: Constraints, orientation: Orientation) {
    when (orientation) {
        Orientation.Horizontal -> {
            check(constraints.hasFixedWidth) {
                "Modifier.bounceable() should receive a fixed width from its parent. Make sure " +
                    "that it is used *after* a fixed-width Modifier in the horizontal axis (like" +
                    " Modifier.fillMaxWidth() or Modifier.width())."
            }
        }
        Orientation.Vertical -> {
            check(constraints.hasFixedHeight) {
                "Modifier.bounceable() should receive a fixed height from its parent. Make sure " +
                    "that it is used *after* a fixed-height Modifier in the vertical axis (like" +
                    " Modifier.fillMaxHeight() or Modifier.height())."
            }
        }
    }
}
