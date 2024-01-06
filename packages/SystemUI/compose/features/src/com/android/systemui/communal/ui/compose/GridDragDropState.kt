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

package com.android.systemui.communal.ui.compose

import android.util.SizeF
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.android.systemui.communal.ui.compose.extensions.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Composable
fun rememberGridDragDropState(
    gridState: LazyGridState,
    contentListState: ContentListState,
    updateDragPositionForRemove: (offset: Offset) -> Boolean,
): GridDragDropState {
    val scope = rememberCoroutineScope()
    val state =
        remember(gridState, contentListState) {
            GridDragDropState(
                state = gridState,
                contentListState = contentListState,
                scope = scope,
                updateDragPositionForRemove = updateDragPositionForRemove
            )
        }
    LaunchedEffect(state) {
        while (true) {
            val diff = state.scrollChannel.receive()
            gridState.scrollBy(diff)
        }
    }
    return state
}

/**
 * Handles drag and drop cards in the glanceable hub. While dragging to move, other items that are
 * affected will dynamically get positioned and the state is tracked by [ContentListState]. When
 * dragging to remove, affected cards will be moved and [updateDragPositionForRemove] is called to
 * check whether the dragged item can be removed. On dragging ends, call [ContentListState.onRemove]
 * to remove the dragged item if condition met and call [ContentListState.onSaveList] to persist any
 * change in ordering.
 */
class GridDragDropState
internal constructor(
    private val state: LazyGridState,
    private val contentListState: ContentListState,
    private val scope: CoroutineScope,
    private val updateDragPositionForRemove: (offset: Offset) -> Boolean
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    var isDraggingToRemove by mutableStateOf(false)
        private set

    internal val scrollChannel = Channel<Float>()

    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(Offset.Zero)
    private var dragStartPointerOffset by mutableStateOf(Offset.Zero)

    internal val draggingItemOffset: Offset
        get() =
            draggingItemLayoutInfo?.let { item ->
                draggingItemInitialOffset + draggingItemDraggedDelta - item.offset.toOffset()
            }
                ?: Offset.Zero

    private val draggingItemLayoutInfo: LazyGridItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    internal fun onDragStart(offset: Offset, contentOffset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                // grid item offset is based off grid content container so we need to deduct
                // before content padding from the initial pointer position
                contentListState.isItemEditable(item.index) &&
                    (offset.x - contentOffset.x).toInt() in item.offset.x..item.offsetEnd.x &&
                    (offset.y - contentOffset.y).toInt() in item.offset.y..item.offsetEnd.y
            }
            ?.apply {
                dragStartPointerOffset = offset - this.offset.toOffset()
                draggingItemIndex = index
                draggingItemInitialOffset = this.offset.toOffset()
            }
    }

    internal fun onDragInterrupted() {
        draggingItemIndex?.let {
            if (isDraggingToRemove) {
                contentListState.onRemove(it)
                isDraggingToRemove = false
                updateDragPositionForRemove(Offset.Zero)
            }
            // persist list editing changes on dragging ends
            contentListState.onSaveList()
            draggingItemIndex = null
        }
        draggingItemDraggedDelta = Offset.Zero
        draggingItemInitialOffset = Offset.Zero
        dragStartPointerOffset = Offset.Zero
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset.toOffset() + draggingItemOffset
        val endOffset = startOffset + draggingItem.size.toSize()
        val middleOffset = startOffset + (endOffset - startOffset) / 2f

        val targetItem =
            state.layoutInfo.visibleItemsInfo.find { item ->
                contentListState.isItemEditable(item.index) &&
                    middleOffset.x.toInt() in item.offset.x..item.offsetEnd.x &&
                    middleOffset.y.toInt() in item.offset.y..item.offsetEnd.y &&
                    draggingItem.index != item.index
            }

        if (targetItem != null) {
            val scrollToIndex =
                if (targetItem.index == state.firstVisibleItemIndex) {
                    draggingItem.index
                } else if (draggingItem.index == state.firstVisibleItemIndex) {
                    targetItem.index
                } else {
                    null
                }
            if (scrollToIndex != null) {
                scope.launch {
                    // this is needed to neutralize automatic keeping the first item first.
                    state.scrollToItem(scrollToIndex, state.firstVisibleItemScrollOffset)
                    contentListState.onMove(draggingItem.index, targetItem.index)
                }
            } else {
                contentListState.onMove(draggingItem.index, targetItem.index)
            }
            draggingItemIndex = targetItem.index
            isDraggingToRemove = false
        } else {
            val overscroll = checkForOverscroll(startOffset, endOffset)
            if (overscroll != 0f) {
                scrollChannel.trySend(overscroll)
            }
            isDraggingToRemove = checkForRemove(startOffset)
        }
    }

    private val LazyGridItemInfo.offsetEnd: IntOffset
        get() = this.offset + this.size

    /** Calculate the amount dragged out of bound on both sides. Returns 0f if not overscrolled */
    private fun checkForOverscroll(startOffset: Offset, endOffset: Offset): Float {
        return when {
            draggingItemDraggedDelta.x > 0 ->
                (endOffset.x - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
            draggingItemDraggedDelta.x < 0 ->
                (startOffset.x - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)
            else -> 0f
        }
    }

    /** Calls the callback with the updated drag position and returns whether to remove the item. */
    private fun checkForRemove(startOffset: Offset): Boolean {
        return if (draggingItemDraggedDelta.y < 0)
            updateDragPositionForRemove(startOffset + dragStartPointerOffset)
        else false
    }
}

fun Modifier.dragContainer(
    dragDropState: GridDragDropState,
    beforeContentPadding: ContentPaddingInPx
): Modifier {
    return pointerInput(dragDropState, beforeContentPadding) {
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                dragDropState.onDrag(offset = offset)
            },
            onDragStart = { offset ->
                dragDropState.onDragStart(
                    offset,
                    Offset(beforeContentPadding.startPadding, beforeContentPadding.topPadding)
                )
            },
            onDragEnd = { dragDropState.onDragInterrupted() },
            onDragCancel = { dragDropState.onDragInterrupted() }
        )
    }
}

/** Wrap LazyGrid item with additional modifier needed for drag and drop. */
@ExperimentalFoundationApi
@Composable
fun LazyGridItemScope.DraggableItem(
    dragDropState: GridDragDropState,
    index: Int,
    enabled: Boolean,
    size: SizeF,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit
) {
    if (!enabled) {
        return Box(modifier = modifier) { content(false) }
    }
    val dragging = index == dragDropState.draggingItemIndex
    val draggingModifier =
        if (dragging) {
            Modifier.zIndex(1f).graphicsLayer {
                translationX = dragDropState.draggingItemOffset.x
                translationY = dragDropState.draggingItemOffset.y
                alpha = if (dragDropState.isDraggingToRemove) 0.5f else 1f
            }
        } else {
            Modifier.animateItemPlacement()
        }

    Box(modifier) {
        if (dragging) {
            WidgetPlaceholderContent(size)
        }
        Box(modifier = draggingModifier, propagateMinConstraints = true) { content(dragging) }
    }
}
