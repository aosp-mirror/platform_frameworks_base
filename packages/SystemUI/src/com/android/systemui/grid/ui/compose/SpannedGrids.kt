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

package com.android.systemui.grid.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Horizontal (non lazy) grid that supports [spans] for its elements.
 *
 * The elements will be laid down vertically first, and then by columns. So assuming LTR layout, it
 * will be (for a span list `[2, 1, 2, 1, 1, 1, 1, 1]` and 4 rows):
 * ```
 * 0  2  5
 * 0  2  6
 * 1  3  7
 *    4
 * ```
 *
 * where repeated numbers show larger span. If an element doesn't fit in a column due to its span,
 * it will start a new column.
 *
 * Elements in [spans] must be in the interval `[1, rows]` ([rows] > 0), and the composables are
 * associated with the corresponding span based on their index.
 *
 * Due to the fact that elements are seen as a linear list that's laid out in a grid, the semantics
 * represent the collection as a list of elements.
 */
@Composable
fun HorizontalSpannedGrid(
    rows: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    spans: List<Int>,
    modifier: Modifier = Modifier,
    composables: @Composable BoxScope.(spanIndex: Int) -> Unit,
) {
    SpannedGrid(
        primarySpaces = rows,
        crossAxisSpacing = rowSpacing,
        mainAxisSpacing = columnSpacing,
        spans = spans,
        isVertical = false,
        modifier = modifier,
        composables = composables,
    )
}

/**
 * Horizontal (non lazy) grid that supports [spans] for its elements.
 *
 * The elements will be laid down horizontally first, and then by rows. So assuming LTR layout, it
 * will be (for a span list `[2, 1, 2, 1, 1, 1, 1, 1]` and 4 columns):
 * ```
 * 0  0  1
 * 2  2  3  4
 * 5  6  7
 * ```
 *
 * where repeated numbers show larger span. If an element doesn't fit in a row due to its span, it
 * will start a new row.
 *
 * Elements in [spans] must be in the interval `[1, columns]` ([columns] > 0), and the composables
 * are associated with the corresponding span based on their index.
 *
 * Due to the fact that elements are seen as a linear list that's laid out in a grid, the semantics
 * represent the collection as a list of elements.
 */
@Composable
fun VerticalSpannedGrid(
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    spans: List<Int>,
    modifier: Modifier = Modifier,
    composables: @Composable BoxScope.(spanIndex: Int) -> Unit,
) {
    SpannedGrid(
        primarySpaces = columns,
        crossAxisSpacing = columnSpacing,
        mainAxisSpacing = rowSpacing,
        spans = spans,
        isVertical = true,
        modifier = modifier,
        composables = composables,
    )
}

@Composable
private fun SpannedGrid(
    primarySpaces: Int,
    crossAxisSpacing: Dp,
    mainAxisSpacing: Dp,
    spans: List<Int>,
    isVertical: Boolean,
    modifier: Modifier = Modifier,
    composables: @Composable BoxScope.(spanIndex: Int) -> Unit,
) {
    val crossAxisArrangement = Arrangement.spacedBy(crossAxisSpacing)
    spans.forEachIndexed { index, span ->
        check(span in 1..primarySpaces) {
            "Span out of bounds. Span at index $index has value of $span which is outside of the " +
                "expected rance of [1, $primarySpaces]"
        }
    }

    if (isVertical) {
        check(crossAxisSpacing >= 0.dp) { "Negative columnSpacing $crossAxisSpacing" }
        check(mainAxisSpacing >= 0.dp) { "Negative rowSpacing $mainAxisSpacing" }
    } else {
        check(mainAxisSpacing >= 0.dp) { "Negative columnSpacing $mainAxisSpacing" }
        check(crossAxisSpacing >= 0.dp) { "Negative rowSpacing $crossAxisSpacing" }
    }

    val totalMainAxisGroups: Int =
        remember(primarySpaces, spans) {
            var currentAccumulated = 0
            var groups = 1
            spans.forEach { span ->
                if (currentAccumulated + span <= primarySpaces) {
                    currentAccumulated += span
                } else {
                    groups += 1
                    currentAccumulated = span
                }
            }
            groups
        }

    val slotPositionsAndSizesCache = remember {
        object {
            var sizes = IntArray(0)
            var positions = IntArray(0)
        }
    }

    Layout(
        {
            (0 until spans.size).map { spanIndex ->
                Box(
                    Modifier.semantics {
                        collectionItemInfo =
                            if (isVertical) {
                                CollectionItemInfo(spanIndex, 1, 0, 1)
                            } else {
                                CollectionItemInfo(0, 1, spanIndex, 1)
                            }
                    }
                ) {
                    composables(spanIndex)
                }
            }
        },
        modifier.semantics { collectionInfo = CollectionInfo(spans.size, 1) },
    ) { measurables, constraints ->
        check(measurables.size == spans.size)
        val crossAxisSize = if (isVertical) constraints.maxWidth else constraints.maxHeight
        check(crossAxisSize != Constraints.Infinity) { "Width must be constrained" }
        if (slotPositionsAndSizesCache.sizes.size != primarySpaces) {
            slotPositionsAndSizesCache.sizes = IntArray(primarySpaces)
            slotPositionsAndSizesCache.positions = IntArray(primarySpaces)
        }
        calculateCellsCrossAxisSize(
            crossAxisSize,
            primarySpaces,
            crossAxisSpacing.roundToPx(),
            slotPositionsAndSizesCache.sizes,
        )
        val cellSizesInCrossAxis = slotPositionsAndSizesCache.sizes

        // with is needed because of the double receiver (Density, Arrangement).
        with(crossAxisArrangement) {
            arrange(
                crossAxisSize,
                slotPositionsAndSizesCache.sizes,
                LayoutDirection.Ltr,
                slotPositionsAndSizesCache.positions,
            )
        }
        val startPositions = slotPositionsAndSizesCache.positions

        val mainAxisSpacingPx = mainAxisSpacing.roundToPx()
        val mainAxisTotalGaps = (totalMainAxisGroups - 1) * mainAxisSpacingPx
        val mainAxisSize = if (isVertical) constraints.maxHeight else constraints.maxWidth
        val mainAxisElementConstraint =
            if (mainAxisSize == Constraints.Infinity) {
                Constraints.Infinity
            } else {
                max(0, (mainAxisSize - mainAxisTotalGaps) / totalMainAxisGroups)
            }

        val mainAxisSizes = IntArray(totalMainAxisGroups) { 0 }

        var currentSlot = 0
        var mainAxisGroup = 0
        val placeables =
            measurables.mapIndexed { index, measurable ->
                val span = spans[index]
                if (currentSlot + span > primarySpaces) {
                    currentSlot = 0
                    mainAxisGroup += 1
                }
                val crossAxisConstraint =
                    calculateWidth(cellSizesInCrossAxis, startPositions, currentSlot, span)
                PlaceResult(
                        measurable.measure(
                            makeConstraint(
                                isVertical,
                                mainAxisElementConstraint,
                                crossAxisConstraint,
                            )
                        ),
                        currentSlot,
                        mainAxisGroup,
                    )
                    .also {
                        currentSlot += span
                        mainAxisSizes[mainAxisGroup] =
                            max(
                                mainAxisSizes[mainAxisGroup],
                                if (isVertical) it.placeable.height else it.placeable.width,
                            )
                    }
            }

        val mainAxisTotalSize = mainAxisTotalGaps + mainAxisSizes.sum()
        val mainAxisStartingPoints =
            mainAxisSizes.runningFold(0) { acc, value -> acc + value + mainAxisSpacingPx }
        val height = if (isVertical) mainAxisTotalSize else crossAxisSize
        val width = if (isVertical) crossAxisSize else mainAxisTotalSize

        layout(width, height) {
            placeables.forEach { (placeable, slot, mainAxisGroup) ->
                val x =
                    if (isVertical) {
                        startPositions[slot]
                    } else {
                        mainAxisStartingPoints[mainAxisGroup]
                    }
                val y =
                    if (isVertical) {
                        mainAxisStartingPoints[mainAxisGroup]
                    } else {
                        startPositions[slot]
                    }
                placeable.placeRelative(x, y)
            }
        }
    }
}

fun makeConstraint(isVertical: Boolean, mainAxisSize: Int, crossAxisSize: Int): Constraints {
    return if (isVertical) {
        Constraints(maxHeight = mainAxisSize, minWidth = crossAxisSize, maxWidth = crossAxisSize)
    } else {
        Constraints(maxWidth = mainAxisSize, minHeight = crossAxisSize, maxHeight = crossAxisSize)
    }
}

private fun calculateWidth(sizes: IntArray, positions: IntArray, startSlot: Int, span: Int): Int {
    val crossAxisSize =
        if (span == 1) {
                sizes[startSlot]
            } else {
                val endSlot = startSlot + span - 1
                positions[endSlot] + sizes[endSlot] - positions[startSlot]
            }
            .coerceAtLeast(0)
    return crossAxisSize
}

private fun calculateCellsCrossAxisSize(
    gridSize: Int,
    slotCount: Int,
    spacingPx: Int,
    outArray: IntArray,
) {
    check(outArray.size == slotCount)
    val gridSizeWithoutSpacing = gridSize - spacingPx * (slotCount - 1)
    val slotSize = gridSizeWithoutSpacing / slotCount
    val remainingPixels = gridSizeWithoutSpacing % slotCount
    outArray.indices.forEach { index ->
        outArray[index] = slotSize + if (index < remainingPixels) 1 else 0
    }
}

private data class PlaceResult(
    val placeable: Placeable,
    val slotIndex: Int,
    val mainAxisGroup: Int,
)
