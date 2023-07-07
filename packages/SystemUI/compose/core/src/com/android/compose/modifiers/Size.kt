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
 *
 */

package com.android.compose.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.InspectorValueInfo
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

// This file was mostly copy pasted from androidx.compose.foundation.layout.Size.kt and contains
// modifiers with lambda parameters to change the (min/max) size of a Composable without triggering
// recomposition when the sizes change.
//
// These should be used instead of the traditional size modifiers when the size changes often, for
// instance when it is animated.
//
// TODO(b/247473910): Remove these modifiers once they can be fully replaced by layout animations
// APIs.

/** @see androidx.compose.foundation.layout.width */
fun Modifier.width(width: Density.() -> Int) =
    this.then(
        SizeModifier(
            minWidth = width,
            maxWidth = width,
            enforceIncoming = true,
            inspectorInfo =
                debugInspectorInfo {
                    name = "width"
                    value = width
                }
        )
    )

/** @see androidx.compose.foundation.layout.height */
fun Modifier.height(height: Density.() -> Int) =
    this.then(
        SizeModifier(
            minHeight = height,
            maxHeight = height,
            enforceIncoming = true,
            inspectorInfo =
                debugInspectorInfo {
                    name = "height"
                    value = height
                }
        )
    )

/** @see androidx.compose.foundation.layout.size */
fun Modifier.size(width: Density.() -> Int, height: Density.() -> Int) =
    this.then(
        SizeModifier(
            minWidth = width,
            maxWidth = width,
            minHeight = height,
            maxHeight = height,
            enforceIncoming = true,
            inspectorInfo =
                debugInspectorInfo {
                    name = "size"
                    properties["width"] = width
                    properties["height"] = height
                }
        )
    )

private val SizeUnspecified: Density.() -> Int = { 0 }

private class SizeModifier(
    private val minWidth: Density.() -> Int = SizeUnspecified,
    private val minHeight: Density.() -> Int = SizeUnspecified,
    private val maxWidth: Density.() -> Int = SizeUnspecified,
    private val maxHeight: Density.() -> Int = SizeUnspecified,
    private val enforceIncoming: Boolean,
    inspectorInfo: InspectorInfo.() -> Unit
) : LayoutModifier, InspectorValueInfo(inspectorInfo) {
    private val Density.targetConstraints: Constraints
        get() {
            val maxWidth =
                if (maxWidth != SizeUnspecified) {
                    maxWidth().coerceAtLeast(0)
                } else {
                    Constraints.Infinity
                }
            val maxHeight =
                if (maxHeight != SizeUnspecified) {
                    maxHeight().coerceAtLeast(0)
                } else {
                    Constraints.Infinity
                }
            val minWidth =
                if (minWidth != SizeUnspecified) {
                    minWidth().coerceAtMost(maxWidth).coerceAtLeast(0).let {
                        if (it != Constraints.Infinity) it else 0
                    }
                } else {
                    0
                }
            val minHeight =
                if (minHeight != SizeUnspecified) {
                    minHeight().coerceAtMost(maxHeight).coerceAtLeast(0).let {
                        if (it != Constraints.Infinity) it else 0
                    }
                } else {
                    0
                }
            return Constraints(
                minWidth = minWidth,
                minHeight = minHeight,
                maxWidth = maxWidth,
                maxHeight = maxHeight
            )
        }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val wrappedConstraints =
            targetConstraints.let { targetConstraints ->
                if (enforceIncoming) {
                    constraints.constrain(targetConstraints)
                } else {
                    val resolvedMinWidth =
                        if (minWidth != SizeUnspecified) {
                            targetConstraints.minWidth
                        } else {
                            constraints.minWidth.coerceAtMost(targetConstraints.maxWidth)
                        }
                    val resolvedMaxWidth =
                        if (maxWidth != SizeUnspecified) {
                            targetConstraints.maxWidth
                        } else {
                            constraints.maxWidth.coerceAtLeast(targetConstraints.minWidth)
                        }
                    val resolvedMinHeight =
                        if (minHeight != SizeUnspecified) {
                            targetConstraints.minHeight
                        } else {
                            constraints.minHeight.coerceAtMost(targetConstraints.maxHeight)
                        }
                    val resolvedMaxHeight =
                        if (maxHeight != SizeUnspecified) {
                            targetConstraints.maxHeight
                        } else {
                            constraints.maxHeight.coerceAtLeast(targetConstraints.minHeight)
                        }
                    Constraints(
                        resolvedMinWidth,
                        resolvedMaxWidth,
                        resolvedMinHeight,
                        resolvedMaxHeight
                    )
                }
            }
        val placeable = measurable.measure(wrappedConstraints)
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int
    ): Int {
        val constraints = targetConstraints
        return if (constraints.hasFixedWidth) {
            constraints.maxWidth
        } else {
            constraints.constrainWidth(measurable.minIntrinsicWidth(height))
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int
    ): Int {
        val constraints = targetConstraints
        return if (constraints.hasFixedHeight) {
            constraints.maxHeight
        } else {
            constraints.constrainHeight(measurable.minIntrinsicHeight(width))
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int
    ): Int {
        val constraints = targetConstraints
        return if (constraints.hasFixedWidth) {
            constraints.maxWidth
        } else {
            constraints.constrainWidth(measurable.maxIntrinsicWidth(height))
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int
    ): Int {
        val constraints = targetConstraints
        return if (constraints.hasFixedHeight) {
            constraints.maxHeight
        } else {
            constraints.constrainHeight(measurable.maxIntrinsicHeight(width))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is SizeModifier) return false
        return minWidth == other.minWidth &&
            minHeight == other.minHeight &&
            maxWidth == other.maxWidth &&
            maxHeight == other.maxHeight &&
            enforceIncoming == other.enforceIncoming
    }

    override fun hashCode() =
        (((((minWidth.hashCode() * 31 + minHeight.hashCode()) * 31) + maxWidth.hashCode()) * 31) +
            maxHeight.hashCode()) * 31
}
