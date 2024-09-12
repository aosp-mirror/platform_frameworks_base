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

package com.android.compose.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a grid with [columns] columns.
 *
 * Child composables will be arranged row by row.
 *
 * Each column is spaced from the columns to its left and right by [horizontalSpacing]. Each cell
 * inside a column is spaced from the cells above and below it with [verticalSpacing].
 */
@Composable
fun VerticalGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 0.dp,
    horizontalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Grid(
        primarySpaces = columns,
        isVertical = true,
        modifier = modifier,
        verticalSpacing = verticalSpacing,
        horizontalSpacing = horizontalSpacing,
        content = content,
    )
}

/**
 * Renders a grid with [rows] rows.
 *
 * Child composables will be arranged column by column.
 *
 * Each column is spaced from the columns to its left and right by [horizontalSpacing]. Each cell
 * inside a column is spaced from the cells above and below it with [verticalSpacing].
 */
@Composable
fun HorizontalGrid(
    rows: Int,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 0.dp,
    horizontalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Grid(
        primarySpaces = rows,
        isVertical = false,
        modifier = modifier,
        verticalSpacing = verticalSpacing,
        horizontalSpacing = horizontalSpacing,
        content = content,
    )
}

@Composable
private fun Grid(
    primarySpaces: Int,
    isVertical: Boolean,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp,
    horizontalSpacing: Dp,
    content: @Composable () -> Unit,
) {
    check(primarySpaces > 0) {
        "Must provide a positive number of ${if (isVertical) "columns" else "rows"}"
    }

    val sizeCache = remember {
        object {
            var rowHeights = intArrayOf()
            var columnWidths = intArrayOf()
        }
    }

    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val cells = measurables.size
        val columns: Int
        val rows: Int
        if (isVertical) {
            columns = primarySpaces
            rows = ceil(cells.toFloat() / primarySpaces).toInt()
        } else {
            columns = ceil(cells.toFloat() / primarySpaces).toInt()
            rows = primarySpaces
        }

        if (sizeCache.rowHeights.size != rows) {
            sizeCache.rowHeights = IntArray(rows) { 0 }
        } else {
            repeat(rows) { i -> sizeCache.rowHeights[i] = 0 }
        }

        if (sizeCache.columnWidths.size != columns) {
            sizeCache.columnWidths = IntArray(columns) { 0 }
        } else {
            repeat(columns) { i -> sizeCache.columnWidths[i] = 0 }
        }

        val totalHorizontalSpacingBetweenChildren =
            ((columns - 1) * horizontalSpacing.toPx()).roundToInt()
        val totalVerticalSpacingBetweenChildren = ((rows - 1) * verticalSpacing.toPx()).roundToInt()
        val childConstraints =
            Constraints(
                maxWidth =
                    if (constraints.maxWidth != Constraints.Infinity) {
                        (constraints.maxWidth - totalHorizontalSpacingBetweenChildren) / columns
                    } else {
                        Constraints.Infinity
                    },
                maxHeight =
                    if (constraints.maxHeight != Constraints.Infinity) {
                        (constraints.maxHeight - totalVerticalSpacingBetweenChildren) / rows
                    } else {
                        Constraints.Infinity
                    },
            )

        val placeables = buildList {
            for (cellIndex in measurables.indices) {
                val column: Int
                val row: Int
                if (isVertical) {
                    column = cellIndex % columns
                    row = cellIndex / columns
                } else {
                    column = cellIndex / rows
                    row = cellIndex % rows
                }

                val placeable = measurables[cellIndex].measure(childConstraints)
                sizeCache.rowHeights[row] = max(sizeCache.rowHeights[row], placeable.height)
                sizeCache.columnWidths[column] =
                    max(sizeCache.columnWidths[column], placeable.width)
                add(placeable)
            }
        }

        var totalWidth = totalHorizontalSpacingBetweenChildren
        for (column in sizeCache.columnWidths.indices) {
            totalWidth += sizeCache.columnWidths[column]
        }

        var totalHeight = totalVerticalSpacingBetweenChildren
        for (row in sizeCache.rowHeights.indices) {
            totalHeight += sizeCache.rowHeights[row]
        }

        layout(totalWidth, totalHeight) {
            var y = 0
            repeat(rows) { row ->
                var x = 0
                var maxChildHeight = 0
                repeat(columns) { column ->
                    val cellIndex = row * columns + column
                    if (cellIndex < cells) {
                        val placeable = placeables[cellIndex]
                        placeable.placeRelative(x, y)
                        x += placeable.width + horizontalSpacing.roundToPx()
                        maxChildHeight = max(maxChildHeight, placeable.height)
                    }
                }
                y += maxChildHeight + verticalSpacing.roundToPx()
            }
        }
    }
}
