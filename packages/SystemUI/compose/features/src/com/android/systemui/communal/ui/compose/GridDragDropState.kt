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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.android.systemui.communal.domain.model.CommunalContentModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Composable
fun rememberGridDragDropState(
    gridState: LazyGridState,
    contentListState: ContentListState
): GridDragDropState {
    val scope = rememberCoroutineScope()
    val state =
        remember(gridState, contentListState) {
            GridDragDropState(state = gridState, contentListState = contentListState, scope = scope)
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
 * dragging to remove, affected cards will be moved and [ContentListState.onRemove] is called to
 * remove the dragged item. On dragging ends, call [ContentListState.onSaveList] to persist the
 * change.
 */
class GridDragDropState
internal constructor(
    private val state: LazyGridState,
    private val contentListState: ContentListState,
    private val scope: CoroutineScope,
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val scrollChannel = Channel<Float>()

    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(Offset.Zero)
    internal val draggingItemOffset: Offset
        get() =
            draggingItemLayoutInfo?.let { item ->
                draggingItemInitialOffset + draggingItemDraggedDelta - item.offset.toOffset()
            }
                ?: Offset.Zero

    private val draggingItemLayoutInfo: LazyGridItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    internal fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                item.isEditable &&
                    offset.x.toInt() in item.offset.x..item.offsetEnd.x &&
                    offset.y.toInt() in item.offset.y..item.offsetEnd.y
            }
            ?.apply {
                draggingItemIndex = index
                draggingItemInitialOffset = this.offset.toOffset()
            }
    }

    internal fun onDragInterrupted() {
        if (draggingItemIndex != null) {
            // persist list editing changes on dragging ends
            contentListState.onSaveList()
            draggingItemIndex = null
        }
        draggingItemDraggedDelta = Offset.Zero
        draggingItemInitialOffset = Offset.Zero
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset.toOffset() + draggingItemOffset
        val endOffset = startOffset + draggingItem.size.toSize()
        val middleOffset = startOffset + (endOffset - startOffset) / 2f

        val targetItem =
            state.layoutInfo.visibleItemsInfo.find { item ->
                item.isEditable &&
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
        } else {
            val overscroll = checkForOverscroll(startOffset, endOffset)
            if (overscroll != 0f) {
                scrollChannel.trySend(overscroll)
            }
            val removeOffset = checkForRemove(startOffset)
            if (removeOffset != 0f) {
                draggingItemIndex?.let {
                    contentListState.onRemove(it)
                    draggingItemIndex = null
                }
            }
        }
    }

    private val LazyGridItemInfo.offsetEnd: IntOffset
        get() = this.offset + this.size

    /** Whether the grid item can be dragged or be a drop target. Only widget card is editable. */
    private val LazyGridItemInfo.isEditable: Boolean
        get() = contentListState.list[this.index] is CommunalContentModel.Widget

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

    // TODO(b/309968801): a temporary solution to decide whether to remove card when it's dragged up
    //  and out of grid. Once we have a taskbar, calculate the intersection of the dragged item with
    //  the Remove button.
    private fun checkForRemove(startOffset: Offset): Float {
        return if (draggingItemDraggedDelta.y < 0)
            (startOffset.y + Dimensions.CardHeightHalf.value - state.layoutInfo.viewportStartOffset)
                .coerceAtMost(0f)
        else 0f
    }
}

private operator fun IntOffset.plus(size: IntSize): IntOffset {
    return IntOffset(x + size.width, y + size.height)
}

private operator fun Offset.plus(size: Size): Offset {
    return Offset(x + size.width, y + size.height)
}

fun Modifier.dragContainer(dragDropState: GridDragDropState): Modifier {
    return pointerInput(dragDropState) {
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                dragDropState.onDrag(offset = offset)
            },
            onDragStart = { offset -> dragDropState.onDragStart(offset) },
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
            }
        } else {
            Modifier.animateItemPlacement()
        }
    Box(modifier = modifier.then(draggingModifier), propagateMinConstraints = true) {
        content(dragging)
    }
}
