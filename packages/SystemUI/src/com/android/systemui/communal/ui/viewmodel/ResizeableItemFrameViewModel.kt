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

package com.android.systemui.communal.ui.viewmodel

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.runtime.snapshotFlow
import com.android.app.tracing.coroutines.coroutineScopeTraced as coroutineScope
import com.android.systemui.lifecycle.ExclusiveActivatable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

enum class DragHandle {
    TOP,
    BOTTOM,
}

data class ResizeInfo(
    /**
     * The number of spans to resize by. A positive number indicates expansion, whereas a negative
     * number indicates shrinking.
     */
    val spans: Int,
    /** The drag handle which was used to resize the element. */
    val fromHandle: DragHandle,
) {
    /** Whether we are expanding. If false, then we are shrinking. */
    val isExpanding = spans > 0
}

class ResizeableItemFrameViewModel : ExclusiveActivatable() {
    private data class GridLayoutInfo(
        val minSpan: Int,
        val maxSpan: Int,
        val heightPerSpanPx: Float,
        val verticalItemSpacingPx: Float,
        val currentRow: Int,
        val currentSpan: Int,
    )

    /**
     * The layout information necessary in order to calculate the pixel offsets of the drag anchor
     * points.
     */
    private val gridLayoutInfo = MutableStateFlow<GridLayoutInfo?>(null)

    val topDragState = AnchoredDraggableState(0, DraggableAnchors { 0 at 0f })
    val bottomDragState = AnchoredDraggableState(0, DraggableAnchors { 0 at 0f })

    /** Emits a [ResizeInfo] when the element is resized using a drag gesture. */
    val resizeInfo: Flow<ResizeInfo> =
        merge(
                snapshotFlow { topDragState.settledValue }.map { ResizeInfo(-it, DragHandle.TOP) },
                snapshotFlow { bottomDragState.settledValue }
                    .map { ResizeInfo(it, DragHandle.BOTTOM) },
            )
            .filter { it.spans != 0 }
            .distinctUntilChanged()

    /**
     * Sets the necessary grid layout information needed for calculating the pixel offsets of the
     * drag anchors.
     */
    fun setGridLayoutInfo(
        verticalItemSpacingPx: Float,
        verticalContentPaddingPx: Float,
        viewportHeightPx: Int,
        maxItemSpan: Int,
        minItemSpan: Int,
        currentRow: Int?,
        currentSpan: Int?,
    ) {
        if (currentSpan == null || currentRow == null) {
            gridLayoutInfo.value = null
            return
        }
        require(maxItemSpan >= minItemSpan) {
            "Maximum item span of $maxItemSpan cannot be less than the minimum span of $minItemSpan"
        }
        require(minItemSpan in 1..maxItemSpan) {
            "Minimum span must be between 1 and $maxItemSpan, but was $minItemSpan"
        }
        require(currentSpan % minItemSpan == 0) {
            "Current span of $currentSpan is not a multiple of the minimum span of $minItemSpan"
        }
        val availableHeight = viewportHeightPx - verticalContentPaddingPx
        val totalSpacing = verticalItemSpacingPx * ((maxItemSpan / minItemSpan) - 1)
        val heightPerSpanPx = (availableHeight - totalSpacing) / maxItemSpan
        gridLayoutInfo.value =
            GridLayoutInfo(
                minSpan = minItemSpan,
                maxSpan = maxItemSpan,
                heightPerSpanPx = heightPerSpanPx,
                verticalItemSpacingPx = verticalItemSpacingPx,
                currentRow = currentRow,
                currentSpan = currentSpan,
            )
    }

    private fun calculateAnchorsForHandle(
        handle: DragHandle,
        layoutInfo: GridLayoutInfo?,
    ): DraggableAnchors<Int> {

        if (layoutInfo == null || !isDragAllowed(handle, layoutInfo)) {
            return DraggableAnchors { 0 at 0f }
        }

        val (
            minItemSpan,
            maxItemSpan,
            heightPerSpanPx,
            verticalSpacingPx,
            currentRow,
            currentSpan,
        ) = layoutInfo

        // The maximum row this handle can be dragged to.
        val maxRow =
            if (handle == DragHandle.TOP) {
                (currentRow + currentSpan - minItemSpan).coerceAtLeast(0)
            } else {
                maxItemSpan
            }

        // The minimum row this handle can be dragged to.
        val minRow =
            if (handle == DragHandle.TOP) {
                0
            } else {
                (currentRow + minItemSpan).coerceAtMost(maxItemSpan)
            }

        // The current row position of this handle
        val currentPosition = if (handle == DragHandle.TOP) currentRow else currentRow + currentSpan

        return DraggableAnchors {
            for (targetRow in minRow..maxRow step minItemSpan) {
                val diff = targetRow - currentPosition
                val spacing = diff / minItemSpan * verticalSpacingPx
                diff at diff * heightPerSpanPx + spacing
            }
        }
    }

    private fun isDragAllowed(handle: DragHandle, layoutInfo: GridLayoutInfo): Boolean {
        val minItemSpan = layoutInfo.minSpan
        val maxItemSpan = layoutInfo.maxSpan
        val currentRow = layoutInfo.currentRow
        val currentSpan = layoutInfo.currentSpan
        val atMinSize = currentSpan == minItemSpan

        // If already at the minimum size and in the first row, item cannot be expanded from the top
        if (handle == DragHandle.TOP && currentRow == 0 && atMinSize) {
            return false
        }

        // If already at the minimum size and occupying the last row, item cannot be expanded from
        // the
        // bottom
        if (handle == DragHandle.BOTTOM && (currentRow + currentSpan) == maxItemSpan && atMinSize) {
            return false
        }

        // If at maximum size, item can only be shrunk from the bottom and not the top.
        if (handle == DragHandle.TOP && currentSpan == maxItemSpan) {
            return false
        }

        return true
    }

    override suspend fun onActivated(): Nothing {
        coroutineScope("ResizeableItemFrameViewModel.onActivated") {
            gridLayoutInfo
                .onEach { layoutInfo ->
                    topDragState.updateAnchors(
                        calculateAnchorsForHandle(DragHandle.TOP, layoutInfo)
                    )
                    bottomDragState.updateAnchors(
                        calculateAnchorsForHandle(DragHandle.BOTTOM, layoutInfo)
                    )
                }
                .launchIn(this)
            awaitCancellation()
        }
    }
}
