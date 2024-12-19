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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapIndexed
import kotlin.math.max

/** Creates a [SpannedGridState] that is remembered across recompositions. */
@Composable
fun rememberSpannedGridState(): SpannedGridState {
    return remember { SpannedGridStateImpl() }
}

/**
 * Horizontal (non lazy) grid that supports spans for its elements.
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
 * Elements in [composables] can provide their span using [SpannedGridScope.span] and have a default
 * span of 1. Spans must be in the interval `[1, columns]` ([columns] > 0).
 *
 * Passing a [SpannedGridState] can be useful to get access to the [SpannedGridState.positions],
 * representing the row and column of each item.
 *
 * Due to the fact that elements are seen as a linear list that's laid out in a grid, the semantics
 * represent the collection as a list of elements.
 */
@Composable
fun HorizontalSpannedGrid(
    rows: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    modifier: Modifier = Modifier,
    state: SpannedGridState = rememberSpannedGridState(),
    composables: @Composable SpannedGridScope.() -> Unit,
) {
    SpannedGrid(
        primarySpaces = rows,
        crossAxisSpacing = rowSpacing,
        mainAxisSpacing = columnSpacing,
        isVertical = false,
        state = state,
        modifier = modifier,
        composables = composables,
    )
}

/**
 * Horizontal (non lazy) grid that supports spans for its elements.
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
 * Elements in [composables] can provide their span using [SpannedGridScope.span] and have a default
 * span of 1. Spans must be in the interval `[1, columns]` ([columns] > 0).
 *
 * Passing a [SpannedGridState] can be useful to get access to the [SpannedGridState.positions],
 * representing the row and column of each item.
 *
 * Due to the fact that elements are seen as a linear list that's laid out in a grid, the semantics
 * represent the collection as a list of elements.
 */
@Composable
fun VerticalSpannedGrid(
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    modifier: Modifier = Modifier,
    state: SpannedGridState = rememberSpannedGridState(),
    composables: @Composable SpannedGridScope.() -> Unit,
) {
    SpannedGrid(
        primarySpaces = columns,
        crossAxisSpacing = columnSpacing,
        mainAxisSpacing = rowSpacing,
        isVertical = true,
        state = state,
        modifier = modifier,
        composables = composables,
    )
}

@Composable
private fun SpannedGrid(
    primarySpaces: Int,
    crossAxisSpacing: Dp,
    mainAxisSpacing: Dp,
    isVertical: Boolean,
    state: SpannedGridState,
    modifier: Modifier = Modifier,
    composables: @Composable SpannedGridScope.() -> Unit,
) {
    state as SpannedGridStateImpl
    SideEffect { state.setOrientation(isVertical) }

    val crossAxisArrangement = Arrangement.spacedBy(crossAxisSpacing)

    if (isVertical) {
        check(crossAxisSpacing >= 0.dp) { "Negative columnSpacing $crossAxisSpacing" }
        check(mainAxisSpacing >= 0.dp) { "Negative rowSpacing $mainAxisSpacing" }
    } else {
        check(mainAxisSpacing >= 0.dp) { "Negative columnSpacing $mainAxisSpacing" }
        check(crossAxisSpacing >= 0.dp) { "Negative rowSpacing $crossAxisSpacing" }
    }

    val slotPositionsAndSizesCache = remember {
        object {
            var sizes = IntArray(0)
            var positions = IntArray(0)
        }
    }

    Layout(
        { SpannedGridScope.composables() },
        modifier.semantics { collectionInfo = CollectionInfo(state.positions.size, 1) },
    ) { measurables, constraints ->
        val spans =
            measurables.fastMapIndexed { index, measurable ->
                measurable.spannedGridParentData.span.also { span ->
                    check(span in 1..primarySpaces) {
                        "Span out of bounds. Span at index $index has value of $span which is " +
                            "outside of the expected rance of [1, $primarySpaces]"
                    }
                }
            }
        var totalMainAxisGroups = 1
        var currentAccumulated = 0
        spans.forEach { span ->
            if (currentAccumulated + span <= primarySpaces) {
                currentAccumulated += span
            } else {
                totalMainAxisGroups += 1
                currentAccumulated = span
            }
        }
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
            state.onPlaceResults(placeables)
        }
    }
}

/** Receiver scope which is used by [VerticalSpannedGrid] and [HorizontalSpannedGrid] */
@Stable
object SpannedGridScope {
    fun Modifier.span(span: Int) = this then SpanElement(span)
}

/** A state object that can be hoisted to observe items positioning */
@Stable
sealed interface SpannedGridState {
    data class Position(val row: Int, val column: Int)

    val positions: List<Position>
}

private class SpannedGridStateImpl : SpannedGridState {
    private val _positions = mutableStateListOf<SpannedGridState.Position>()
    override val positions
        get() = _positions

    private var isVertical by mutableStateOf(false)

    fun onPlaceResults(placeResults: List<PlaceResult>) {
        _positions.clear()
        _positions.addAll(
            placeResults.fastMap { placeResult ->
                SpannedGridState.Position(
                    row = if (isVertical) placeResult.mainAxisGroup else placeResult.slotIndex,
                    column = if (isVertical) placeResult.slotIndex else placeResult.mainAxisGroup,
                )
            }
        )
    }

    fun setOrientation(isVertical: Boolean) {
        this.isVertical = isVertical
    }
}

private fun makeConstraint(
    isVertical: Boolean,
    mainAxisSize: Int,
    crossAxisSize: Int,
): Constraints {
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

private val IntrinsicMeasurable.spannedGridParentData: SpannedGridParentData?
    get() = parentData as? SpannedGridParentData

private val SpannedGridParentData?.span: Int
    get() = this?.span ?: 1

private data class SpannedGridParentData(val span: Int = 1)

private data class SpanElement(val span: Int) : ModifierNodeElement<SpanNode>() {
    override fun create(): SpanNode {
        return SpanNode(span)
    }

    override fun update(node: SpanNode) {
        node.span = span
    }
}

private class SpanNode(var span: Int) : ParentDataModifierNode, Modifier.Node() {
    override fun Density.modifyParentData(parentData: Any?): Any? {
        return ((parentData as? SpannedGridParentData) ?: SpannedGridParentData()).copy(span = span)
    }
}
