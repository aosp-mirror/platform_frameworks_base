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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import com.android.systemui.Flags.communalWidgetResizing
import com.android.systemui.communal.ui.compose.extensions.firstItemAtOffset
import com.android.systemui.communal.ui.compose.extensions.plus
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private fun Float.directional(origin: LayoutDirection, current: LayoutDirection): Float =
    if (origin == current) this else -this

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
                updateDragPositionForRemove = updateDragPositionForRemove,
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
    private val updateDragPositionForRemove: (offset: Offset) -> Boolean,
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
            } ?: Offset.Zero

    private val draggingItemLayoutInfo: LazyGridItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    /**
     * Called when dragging is initiated.
     *
     * @return {@code True} if dragging a grid item, {@code False} otherwise.
     */
    internal fun onDragStart(
        offset: Offset,
        screenWidth: Int,
        layoutDirection: LayoutDirection,
        contentOffset: Offset,
    ): Boolean {
        val normalizedOffset =
            Offset(
                if (layoutDirection == LayoutDirection.Ltr) offset.x else screenWidth - offset.x,
                offset.y,
            )
        state.layoutInfo.visibleItemsInfo
            .filter { item -> contentListState.isItemEditable(item.index) }
            // grid item offset is based off grid content container so we need to deduct
            // before content padding from the initial pointer position
            .firstItemAtOffset(normalizedOffset - contentOffset)
            ?.apply {
                dragStartPointerOffset = normalizedOffset - this.offset.toOffset()
                draggingItemIndex = index
                draggingItemInitialOffset = this.offset.toOffset()
                return true
            }

        return false
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

    internal fun onDrag(offset: Offset, layoutDirection: LayoutDirection) {
        // Adjust offset to match the layout direction
        draggingItemDraggedDelta +=
            Offset(offset.x.directional(LayoutDirection.Ltr, layoutDirection), offset.y)

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset.toOffset() + draggingItemOffset
        val endOffset = startOffset + draggingItem.size.toSize()
        val middleOffset = startOffset + (endOffset - startOffset) / 2f

        val targetItem =
            state.layoutInfo.visibleItemsInfo
                .asSequence()
                .filter { item -> contentListState.isItemEditable(item.index) }
                .filter { item -> draggingItem.index != item.index }
                .firstItemAtOffset(middleOffset)

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
    layoutDirection: LayoutDirection,
    screenWidth: Int,
    contentOffset: Offset,
    viewModel: BaseCommunalViewModel,
): Modifier {
    return this.then(
        pointerInput(dragDropState, contentOffset) {
            detectDragGesturesAfterLongPress(
                onDrag = { change, offset ->
                    change.consume()
                    dragDropState.onDrag(offset, layoutDirection)
                },
                onDragStart = { offset ->
                    if (
                        dragDropState.onDragStart(
                            offset,
                            screenWidth,
                            layoutDirection,
                            contentOffset,
                        )
                    ) {
                        viewModel.onReorderWidgetStart()
                    }
                },
                onDragEnd = {
                    dragDropState.onDragInterrupted()
                    viewModel.onReorderWidgetEnd()
                },
                onDragCancel = {
                    dragDropState.onDragInterrupted()
                    viewModel.onReorderWidgetCancel()
                },
            )
        }
    )
}

/** Wrap LazyGrid item with additional modifier needed for drag and drop. */
@ExperimentalFoundationApi
@Composable
fun LazyGridItemScope.DraggableItem(
    dragDropState: GridDragDropState,
    index: Int,
    enabled: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    if (!enabled) {
        return content(false)
    }

    val dragging = index == dragDropState.draggingItemIndex
    val itemAlpha: Float by
        animateFloatAsState(
            targetValue = if (dragDropState.isDraggingToRemove) 0.5f else 1f,
            label = "DraggableItemAlpha",
        )
    val direction = LocalLayoutDirection.current
    val draggingModifier =
        if (dragging) {
            Modifier.graphicsLayer {
                translationX =
                    dragDropState.draggingItemOffset.x.directional(LayoutDirection.Ltr, direction)
                translationY = dragDropState.draggingItemOffset.y
                alpha = itemAlpha
            }
        } else {
            Modifier.animateItem()
        }

    // Animate the highlight alpha manually as alpha modifier (and AnimatedVisibility) clips the
    // widget to bounds, which cuts off the highlight as we are drawing outside the widget bounds.
    val highlightSelected = !communalWidgetResizing() && selected
    val alpha by
        animateFloatAsState(
            targetValue =
                if ((dragging || highlightSelected) && !dragDropState.isDraggingToRemove) {
                    1f
                } else {
                    0f
                },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "Widget outline alpha",
        )

    Box(modifier) {
        HighlightedItem(Modifier.matchParentSize(), alpha = alpha)
        Box(draggingModifier) { content(dragging) }
    }
}
