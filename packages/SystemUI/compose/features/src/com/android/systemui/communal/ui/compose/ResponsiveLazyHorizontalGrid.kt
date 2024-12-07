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

package com.android.systemui.communal.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times

/**
 * Renders a responsive [LazyHorizontalGrid] with dynamic columns and rows. Each cell will maintain
 * the specified aspect ratio, but is otherwise resizeable in order to best fill the available
 * space.
 */
@Composable
fun ResponsiveLazyHorizontalGrid(
    cellAspectRatio: Float,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    setContentOffset: (offset: Offset) -> Unit = {},
    minContentPadding: PaddingValues = PaddingValues(0.dp),
    minHorizontalArrangement: Dp = 0.dp,
    minVerticalArrangement: Dp = 0.dp,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    content: LazyGridScope.(sizeInfo: SizeInfo) -> Unit,
) {
    check(cellAspectRatio > 0f) { "Aspect ratio must be greater than 0, but was $cellAspectRatio" }
    check(minHorizontalArrangement.value >= 0f && minVerticalArrangement.value >= 0f) {
        "Horizontal and vertical arrangements must be non-negative, but were " +
            "$minHorizontalArrangement and $minVerticalArrangement, respectively."
    }
    BoxWithConstraints(modifier) {
        val gridSize = rememberGridSize(maxWidth = maxWidth, maxHeight = maxHeight)
        val layoutDirection = LocalLayoutDirection.current
        val density = LocalDensity.current

        val minStartPadding = minContentPadding.calculateStartPadding(layoutDirection)
        val minEndPadding = minContentPadding.calculateEndPadding(layoutDirection)
        val minTopPadding = minContentPadding.calculateTopPadding()
        val minBottomPadding = minContentPadding.calculateBottomPadding()
        val minHorizontalPadding = minStartPadding + minEndPadding
        val minVerticalPadding = minTopPadding + minBottomPadding

        // Determine the maximum allowed cell width and height based on the available width and
        // height, and the desired number of columns and rows.
        val maxCellWidth =
            calculateCellSize(
                availableSpace = maxWidth,
                padding = minHorizontalPadding,
                numCells = gridSize.width,
                cellSpacing = minHorizontalArrangement,
            )
        val maxCellHeight =
            calculateCellSize(
                availableSpace = maxHeight,
                padding = minVerticalPadding,
                numCells = gridSize.height,
                cellSpacing = minVerticalArrangement,
            )

        // Constrain the max size to the desired aspect ratio.
        val finalSize =
            calculateClosestSize(
                maxWidth = maxCellWidth,
                maxHeight = maxCellHeight,
                aspectRatio = cellAspectRatio,
            )

        // Determine how much space in each dimension we've used up, and how much we have left as
        // extra space. Distribute the extra space evenly along the content padding.
        val usedWidth =
            calculateUsedSpace(
                    cellSize = finalSize.width,
                    numCells = gridSize.width,
                    padding = minHorizontalPadding,
                    cellSpacing = minHorizontalArrangement,
                )
                .coerceAtMost(maxWidth)
        val usedHeight =
            calculateUsedSpace(
                    cellSize = finalSize.height,
                    numCells = gridSize.height,
                    padding = minVerticalPadding,
                    cellSpacing = minVerticalArrangement,
                )
                .coerceAtMost(maxHeight)
        val extraWidth = maxWidth - usedWidth
        val extraHeight = maxHeight - usedHeight

        val finalStartPadding = minStartPadding + extraWidth / 2
        val finalTopPadding = minTopPadding + extraHeight / 2

        val finalContentPadding =
            PaddingValues(
                start = finalStartPadding,
                end = minEndPadding + extraWidth / 2,
                top = finalTopPadding,
                bottom = minBottomPadding + extraHeight / 2,
            )

        with(density) { setContentOffset(Offset(finalStartPadding.toPx(), finalTopPadding.toPx())) }

        LazyHorizontalGrid(
            rows = GridCells.Fixed(gridSize.height),
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = finalContentPadding,
            horizontalArrangement = Arrangement.spacedBy(minHorizontalArrangement),
            verticalArrangement = Arrangement.spacedBy(minVerticalArrangement),
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            overscrollEffect = overscrollEffect,
        ) {
            content(
                SizeInfo(
                    cellSize = finalSize,
                    contentPadding = finalContentPadding,
                    verticalArrangement = minVerticalArrangement,
                    maxHeight = maxHeight,
                    gridSize = gridSize,
                )
            )
        }
    }
}

private fun calculateCellSize(availableSpace: Dp, padding: Dp, numCells: Int, cellSpacing: Dp): Dp =
    (availableSpace - padding - cellSpacing * (numCells - 1)) / numCells

private fun calculateUsedSpace(cellSize: Dp, numCells: Int, padding: Dp, cellSpacing: Dp): Dp =
    cellSize * numCells + padding + (numCells - 1) * cellSpacing

private fun calculateClosestSize(maxWidth: Dp, maxHeight: Dp, aspectRatio: Float): DpSize {
    return if (maxWidth / maxHeight > aspectRatio) {
        // Target is too wide, shrink width
        DpSize(maxHeight * aspectRatio, maxHeight)
    } else {
        // Target is too tall, shrink height
        DpSize(maxWidth, maxWidth / aspectRatio)
    }
}

/**
 * Provides size info of the responsive grid, since the size is dynamic.
 *
 * @property cellSize The size of each cell in the grid.
 * @property verticalArrangement The space between rows in the grid.
 * @property gridSize The size of the grid, in cell units.
 * @property availableHeight The maximum height an item in the grid may occupy.
 * @property contentPadding The padding around the content of the grid.
 */
data class SizeInfo(
    val cellSize: DpSize,
    val verticalArrangement: Dp,
    val gridSize: IntSize,
    val contentPadding: PaddingValues,
    private val maxHeight: Dp,
) {
    val availableHeight: Dp
        get() =
            maxHeight -
                contentPadding.calculateBottomPadding() -
                contentPadding.calculateTopPadding()

    /** Calculates the height in dp of a certain number of rows. */
    fun calculateHeight(numRows: Int): Dp {
        return numRows * cellSize.height + (numRows - 1) * verticalArrangement
    }
}

@Composable
private fun rememberGridSize(maxWidth: Dp, maxHeight: Dp): IntSize {
    val configuration = LocalConfiguration.current
    val orientation = configuration.orientation

    return remember(orientation, maxWidth, maxHeight) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            IntSize(
                width = calculateNumCellsWidth(maxWidth),
                height = calculateNumCellsHeight(maxHeight),
            )
        } else {
            // In landscape we invert the rows/columns to ensure we match the same area as portrait.
            // This keeps the number of elements in the grid consistent when changing orientation.
            IntSize(
                width = calculateNumCellsHeight(maxWidth),
                height = calculateNumCellsWidth(maxHeight),
            )
        }
    }
}

private fun calculateNumCellsWidth(width: Dp) =
    // See https://developer.android.com/develop/ui/views/layout/use-window-size-classes
    when {
        width >= 840.dp -> 3
        width >= 600.dp -> 2
        else -> 1
    }

private fun calculateNumCellsHeight(height: Dp) =
    // See https://developer.android.com/develop/ui/views/layout/use-window-size-classes
    when {
        height >= 900.dp -> 3
        height >= 480.dp -> 2
        else -> 1
    }
