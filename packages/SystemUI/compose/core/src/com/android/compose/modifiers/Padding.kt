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
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.InspectorValueInfo
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset

// This file was mostly copy/pasted from by androidx.compose.foundation.layout.Padding.kt and
// contains modifiers with lambda parameters to change the padding of a Composable without
// triggering recomposition when the paddings change.
//
// These should be used instead of the traditional size modifiers when the size changes often, for
// instance when it is animated.
//
// TODO(b/247473910): Remove these modifiers once they can be fully replaced by layout animations
// APIs.

/** @see androidx.compose.foundation.layout.padding */
fun Modifier.padding(
    start: Density.() -> Int = PaddingUnspecified,
    top: Density.() -> Int = PaddingUnspecified,
    end: Density.() -> Int = PaddingUnspecified,
    bottom: Density.() -> Int = PaddingUnspecified,
) =
    this.then(
        PaddingModifier(
            start,
            top,
            end,
            bottom,
            rtlAware = true,
            inspectorInfo =
                debugInspectorInfo {
                    name = "padding"
                    properties["start"] = start
                    properties["top"] = top
                    properties["end"] = end
                    properties["bottom"] = bottom
                }
        )
    )

/** @see androidx.compose.foundation.layout.padding */
fun Modifier.padding(
    horizontal: Density.() -> Int = PaddingUnspecified,
    vertical: Density.() -> Int = PaddingUnspecified,
): Modifier {
    return this.then(
        PaddingModifier(
            start = horizontal,
            top = vertical,
            end = horizontal,
            bottom = vertical,
            rtlAware = true,
            inspectorInfo =
                debugInspectorInfo {
                    name = "padding"
                    properties["horizontal"] = horizontal
                    properties["vertical"] = vertical
                }
        )
    )
}

private val PaddingUnspecified: Density.() -> Int = { 0 }

private class PaddingModifier(
    val start: Density.() -> Int,
    val top: Density.() -> Int,
    val end: Density.() -> Int,
    val bottom: Density.() -> Int,
    val rtlAware: Boolean,
    inspectorInfo: InspectorInfo.() -> Unit
) : LayoutModifier, InspectorValueInfo(inspectorInfo) {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val start = start()
        val top = top()
        val end = end()
        val bottom = bottom()

        val horizontal = start + end
        val vertical = top + bottom

        val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))

        val width = constraints.constrainWidth(placeable.width + horizontal)
        val height = constraints.constrainHeight(placeable.height + vertical)
        return layout(width, height) {
            if (rtlAware) {
                placeable.placeRelative(start, top)
            } else {
                placeable.place(start, top)
            }
        }
    }

    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + end.hashCode()
        result = 31 * result + bottom.hashCode()
        result = 31 * result + rtlAware.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        val otherModifier = other as? PaddingModifier ?: return false
        return start == otherModifier.start &&
            top == otherModifier.top &&
            end == otherModifier.end &&
            bottom == otherModifier.bottom &&
            rtlAware == otherModifier.rtlAware
    }
}
