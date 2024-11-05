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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastIsFinite
import androidx.compose.ui.zIndex
import com.android.compose.modifiers.thenIf
import com.android.systemui.communal.ui.viewmodel.DragHandle
import com.android.systemui.communal.ui.viewmodel.ResizeInfo
import com.android.systemui.communal.ui.viewmodel.ResizeableItemFrameViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

@Composable
private fun UpdateGridLayoutInfo(
    viewModel: ResizeableItemFrameViewModel,
    key: String,
    gridState: LazyGridState,
    gridContentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    minHeightPx: Int,
    maxHeightPx: Int,
    resizeMultiple: Int,
    currentSpan: GridItemSpan,
) {
    val density = LocalDensity.current
    LaunchedEffect(
        density,
        viewModel,
        key,
        gridState,
        gridContentPadding,
        verticalArrangement,
        minHeightPx,
        maxHeightPx,
        resizeMultiple,
        currentSpan,
    ) {
        val verticalItemSpacingPx = with(density) { verticalArrangement.spacing.toPx() }
        val verticalContentPaddingPx =
            with(density) {
                (gridContentPadding.calculateTopPadding() +
                        gridContentPadding.calculateBottomPadding())
                    .toPx()
            }

        combine(
                snapshotFlow { gridState.layoutInfo.maxSpan },
                snapshotFlow { gridState.layoutInfo.viewportSize.height },
                snapshotFlow {
                    gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                },
                ::Triple,
            )
            .collectLatest { (maxItemSpan, viewportHeightPx, itemInfo) ->
                viewModel.setGridLayoutInfo(
                    verticalItemSpacingPx = verticalItemSpacingPx,
                    currentRow = itemInfo?.row,
                    maxHeightPx = maxHeightPx,
                    minHeightPx = minHeightPx,
                    currentSpan = currentSpan.currentLineSpan,
                    resizeMultiple = resizeMultiple,
                    totalSpans = maxItemSpan,
                    viewportHeightPx = viewportHeightPx,
                    verticalContentPaddingPx = verticalContentPaddingPx,
                )
            }
    }
}

@Composable
private fun BoxScope.DragHandle(
    handle: DragHandle,
    dragState: AnchoredDraggableState<Int>,
    outlinePadding: Dp,
    brush: Brush,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val directionalModifier = if (handle == DragHandle.TOP) -1 else 1
    val alignment = if (handle == DragHandle.TOP) Alignment.TopCenter else Alignment.BottomCenter
    Box(
        modifier
            .align(alignment)
            .graphicsLayer {
                translationY =
                    directionalModifier * (size.height / 2 + outlinePadding.toPx()) +
                        (dragState.offset.takeIf { it.fastIsFinite() } ?: 0f)
            }
            .anchoredDraggable(dragState, Orientation.Vertical)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (dragState.anchors.size > 1) {
                drawCircle(
                    brush = brush,
                    radius = outlinePadding.toPx(),
                    center = Offset(size.width / 2, size.height / 2),
                    alpha = alpha(),
                )
            }
        }
    }
}

/**
 * Draws a frame around the content with drag handles on the top and bottom of the content.
 *
 * @param key The unique key of this element, must be the same key used in the [LazyGridState].
 * @param currentSpan The current span size of this item in the grid.
 * @param gridState The [LazyGridState] for the grid containing this item.
 * @param gridContentPadding The content padding used for the grid, needed for determining offsets.
 * @param verticalArrangement The vertical arrangement of the grid items.
 * @param modifier Optional modifier to apply to the frame.
 * @param enabled Whether resizing is enabled.
 * @param outlinePadding The padding to apply around the entire frame, in [Dp]
 * @param outlineColor Optional color to make the outline around the content.
 * @param cornerRadius Optional radius to give to the outline around the content.
 * @param strokeWidth Optional stroke width to draw the outline with.
 * @param minHeightPx Optional minimum height in pixels that this widget can be resized to.
 * @param maxHeightPx Optional maximum height in pixels that this widget can be resized to.
 * @param resizeMultiple Optional number of spans that we allow resizing by. For example, if set to
 *   3, then we only allow resizing in multiples of 3 spans.
 * @param alpha Optional function to provide an alpha value for the outline. Can be used to fade the
 *   outline in and out. This is wrapped in a function for performance, as the value is only
 *   accessed during the draw phase.
 * @param onResize Optional callback which gets executed when the item is resized to a new span.
 * @param content The content to draw inside the frame.
 */
@Composable
fun ResizableItemFrame(
    key: String,
    currentSpan: GridItemSpan,
    gridState: LazyGridState,
    gridContentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlinePadding: Dp = 8.dp,
    outlineColor: Color = MaterialTheme.colorScheme.primary,
    cornerRadius: Dp = 37.dp,
    strokeWidth: Dp = 3.dp,
    minHeightPx: Int = 0,
    maxHeightPx: Int = Int.MAX_VALUE,
    resizeMultiple: Int = 1,
    alpha: () -> Float = { 1f },
    viewModel: ResizeableItemFrameViewModel,
    onResize: (info: ResizeInfo) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val brush = SolidColor(outlineColor)
    val onResizeUpdated by rememberUpdatedState(onResize)
    val dragHandleHeight = verticalArrangement.spacing - outlinePadding * 2
    val isDragging by
        remember(viewModel) {
            derivedStateOf {
                val topOffset = viewModel.topDragState.offset.takeIf { it.fastIsFinite() } ?: 0f
                val bottomOffset =
                    viewModel.bottomDragState.offset.takeIf { it.fastIsFinite() } ?: 0f
                topOffset > 0 || bottomOffset > 0
            }
        }

    // Draw content surrounded by drag handles at top and bottom. Allow drag handles
    // to overlap content.
    Box(modifier.thenIf(isDragging) { Modifier.zIndex(1f) }) {
        content()

        if (enabled) {
            DragHandle(
                handle = DragHandle.TOP,
                dragState = viewModel.topDragState,
                outlinePadding = outlinePadding,
                brush = brush,
                alpha = alpha,
                modifier = Modifier.fillMaxWidth().height(dragHandleHeight),
            )

            DragHandle(
                handle = DragHandle.BOTTOM,
                dragState = viewModel.bottomDragState,
                outlinePadding = outlinePadding,
                brush = brush,
                alpha = alpha,
                modifier = Modifier.fillMaxWidth().height(dragHandleHeight),
            )

            // Draw outline around the element.
            Canvas(modifier = Modifier.matchParentSize()) {
                val paddingPx = outlinePadding.toPx()
                val topOffset = viewModel.topDragState.offset.takeIf { it.fastIsFinite() } ?: 0f
                val bottomOffset =
                    viewModel.bottomDragState.offset.takeIf { it.fastIsFinite() } ?: 0f
                drawRoundRect(
                    brush,
                    alpha = alpha(),
                    topLeft = Offset(-paddingPx, topOffset + -paddingPx),
                    size =
                        Size(
                            width = size.width + paddingPx * 2,
                            height = -topOffset + bottomOffset + size.height + paddingPx * 2,
                        ),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                    style = Stroke(width = strokeWidth.toPx()),
                )
            }

            UpdateGridLayoutInfo(
                viewModel = viewModel,
                key = key,
                gridState = gridState,
                currentSpan = currentSpan,
                gridContentPadding = gridContentPadding,
                verticalArrangement = verticalArrangement,
                minHeightPx = minHeightPx,
                maxHeightPx = maxHeightPx,
                resizeMultiple = resizeMultiple,
            )
            LaunchedEffect(viewModel) {
                viewModel.resizeInfo.collectLatest { info -> onResizeUpdated(info) }
            }
        }
    }
}
