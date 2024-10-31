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
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.snapshotFlow
import com.android.app.tracing.coroutines.coroutineScopeTraced as coroutineScope
import com.android.systemui.lifecycle.ExclusiveActivatable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign
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
    data class GridLayoutInfo(
        val currentRow: Int,
        val currentSpan: Int,
        val maxHeightPx: Int,
        val minHeightPx: Int,
        val resizeMultiple: Int,
        val totalSpans: Int,
        private val heightPerSpanPx: Float,
        private val verticalItemSpacingPx: Float,
    ) {
        fun getPxOffsetForResize(spans: Int): Int =
            (spans * (heightPerSpanPx + verticalItemSpacingPx)).toInt()

        private fun getSpansForPx(height: Int): Int =
            ceil((height + verticalItemSpacingPx) / (heightPerSpanPx + verticalItemSpacingPx))
                .toInt()
                .coerceIn(resizeMultiple, totalSpans)

        private fun roundDownToMultiple(spans: Int): Int =
            floor(spans.toDouble() / resizeMultiple).toInt() * resizeMultiple

        val maxSpans: Int
            get() = roundDownToMultiple(getSpansForPx(maxHeightPx)).coerceAtLeast(currentSpan)

        val minSpans: Int
            get() = roundDownToMultiple(getSpansForPx(minHeightPx)).coerceAtMost(currentSpan)
    }

    /** Check if widget can expanded based on current drag states */
    fun canExpand(): Boolean {
        return getNextAnchor(bottomDragState, moveUp = false) != null ||
            getNextAnchor(topDragState, moveUp = true) != null
    }

    /** Check if widget can shrink based on current drag states */
    fun canShrink(): Boolean {
        return getNextAnchor(bottomDragState, moveUp = true) != null ||
            getNextAnchor(topDragState, moveUp = false) != null
    }

    /** Get the next anchor value in the specified direction */
    private fun getNextAnchor(state: AnchoredDraggableState<Int>, moveUp: Boolean): Int? {
        var nextAnchor: Int? = null
        var nextAnchorDiff = Int.MAX_VALUE
        val currentValue = state.currentValue

        for (i in 0 until state.anchors.size) {
            val anchor = state.anchors.anchorAt(i) ?: continue
            if (anchor == currentValue) continue

            val diff =
                if (moveUp) {
                    currentValue - anchor
                } else {
                    anchor - currentValue
                }

            if (diff in 1..<nextAnchorDiff) {
                nextAnchor = anchor
                nextAnchorDiff = diff
            }
        }

        return nextAnchor
    }

    /** Handle expansion to the next anchor */
    suspend fun expandToNextAnchor() {
        if (!canExpand()) return
        val bottomAnchor = getNextAnchor(state = bottomDragState, moveUp = false)
        if (bottomAnchor != null) {
            bottomDragState.snapTo(bottomAnchor)
            return
        }
        val topAnchor =
            getNextAnchor(
                state = topDragState,
                moveUp = true, // Moving up to expand
            )
        topAnchor?.let { topDragState.snapTo(it) }
    }

    /** Handle shrinking to the next anchor */
    suspend fun shrinkToNextAnchor() {
        if (!canShrink()) return
        val topAnchor = getNextAnchor(state = topDragState, moveUp = false)
        if (topAnchor != null) {
            topDragState.snapTo(topAnchor)
            return
        }
        val bottomAnchor = getNextAnchor(state = bottomDragState, moveUp = true)
        bottomAnchor?.let { bottomDragState.snapTo(it) }
    }

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
        currentRow: Int?,
        maxHeightPx: Int,
        minHeightPx: Int,
        currentSpan: Int,
        resizeMultiple: Int,
        totalSpans: Int,
        viewportHeightPx: Int,
        verticalContentPaddingPx: Float,
    ) {
        if (currentRow == null) {
            gridLayoutInfo.value = null
            return
        }
        require(maxHeightPx >= minHeightPx) {
            "Maximum item span of $maxHeightPx cannot be less than the minimum span of $minHeightPx"
        }

        require(currentSpan <= totalSpans) {
            "Current span ($currentSpan) cannot exceed the total number of spans ($totalSpans)"
        }

        require(resizeMultiple > 0) {
            "Resize multiple ($resizeMultiple) must be a positive integer"
        }
        val availableHeight = viewportHeightPx - verticalContentPaddingPx
        val heightPerSpanPx =
            (availableHeight - (totalSpans - 1) * verticalItemSpacingPx) / totalSpans

        gridLayoutInfo.value =
            GridLayoutInfo(
                heightPerSpanPx = heightPerSpanPx,
                verticalItemSpacingPx = verticalItemSpacingPx,
                currentRow = currentRow,
                currentSpan = currentSpan,
                maxHeightPx = maxHeightPx.coerceAtMost(availableHeight.toInt()),
                minHeightPx = minHeightPx,
                resizeMultiple = resizeMultiple,
                totalSpans = totalSpans,
            )
    }

    private fun calculateAnchorsForHandle(
        handle: DragHandle,
        layoutInfo: GridLayoutInfo?,
    ): DraggableAnchors<Int> {

        if (layoutInfo == null || (!isDragAllowed(handle, layoutInfo))) {
            return DraggableAnchors { 0 at 0f }
        }
        val currentRow = layoutInfo.currentRow
        val currentSpan = layoutInfo.currentSpan
        val minItemSpan = layoutInfo.minSpans
        val maxItemSpan = layoutInfo.maxSpans
        val totalSpans = layoutInfo.totalSpans

        // The maximum row this handle can be dragged to.
        val maxRow =
            if (handle == DragHandle.TOP) {
                (currentRow + currentSpan - minItemSpan).coerceAtLeast(0)
            } else {
                (currentRow + maxItemSpan).coerceAtMost(totalSpans)
            }

        // The minimum row this handle can be dragged to.
        val minRow =
            if (handle == DragHandle.TOP) {
                (currentRow + currentSpan - maxItemSpan).coerceAtLeast(0)
            } else {
                (currentRow + minItemSpan).coerceAtMost(totalSpans)
            }

        // The current row position of this handle
        val currentPosition = if (handle == DragHandle.TOP) currentRow else currentRow + currentSpan

        return DraggableAnchors {
            for (targetRow in minRow..maxRow step layoutInfo.resizeMultiple) {
                val diff = targetRow - currentPosition
                val pixelOffset = (layoutInfo.getPxOffsetForResize(abs(diff)) * diff.sign).toFloat()
                diff at pixelOffset
            }
        }
    }

    private fun isDragAllowed(handle: DragHandle, layoutInfo: GridLayoutInfo): Boolean {
        val minItemSpan = layoutInfo.minSpans
        val maxItemSpan = layoutInfo.maxSpans
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
