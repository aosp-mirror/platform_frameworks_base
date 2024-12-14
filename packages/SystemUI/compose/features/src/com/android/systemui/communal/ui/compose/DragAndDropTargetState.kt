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

import android.content.ClipDescription
import android.view.DragEvent
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.ui.compose.extensions.firstItemAtOffset
import com.android.systemui.communal.util.WidgetPickerIntentUtils
import com.android.systemui.communal.util.WidgetPickerIntentUtils.getWidgetExtraFromIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Holds state associated with dragging and dropping items from other activities into the lazy grid.
 *
 * @see dragAndDropTarget
 */
@Composable
internal fun rememberDragAndDropTargetState(
    gridState: LazyGridState,
    contentOffset: Offset,
    contentListState: ContentListState,
): DragAndDropTargetState {
    val scope = rememberCoroutineScope()
    val autoScrollThreshold = with(LocalDensity.current) { 60.dp.toPx() }
    val state =
        remember(gridState, contentOffset, contentListState, autoScrollThreshold, scope) {
            DragAndDropTargetState(
                state = gridState,
                contentOffset = contentOffset,
                contentListState = contentListState,
                autoScrollThreshold = autoScrollThreshold,
                scope = scope,
            )
        }
    LaunchedEffect(state) {
        for (diff in state.scrollChannel) {
            gridState.scrollBy(diff)
        }
    }
    return state
}

/**
 * Attaches a listener for drag and drop events from other activities.
 *
 * @see androidx.compose.foundation.draganddrop.dragAndDropTarget
 * @see DragEvent
 */
@Composable
internal fun Modifier.dragAndDropTarget(
    dragDropTargetState: DragAndDropTargetState,
): Modifier {
    val state by rememberUpdatedState(dragDropTargetState)

    return this then
        Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = accept@{ startEvent ->
                    startEvent.mimeTypes().any { it == ClipDescription.MIMETYPE_TEXT_INTENT }
                },
            target =
                object : DragAndDropTarget {
                    override fun onStarted(event: DragAndDropEvent) {
                        state.onStarted()
                    }

                    override fun onMoved(event: DragAndDropEvent) {
                        state.onMoved(event)
                    }

                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        return state.onDrop(event)
                    }

                    override fun onExited(event: DragAndDropEvent) {
                        state.onExited()
                    }

                    override fun onEnded(event: DragAndDropEvent) {
                        state.onEnded()
                    }
                }
        )
}

/**
 * Handles dropping of an item coming from a different activity (e.g. widget picker) in to the grid
 * corresponding to the provided [LazyGridState].
 *
 * Adds a placeholder container to highlight the anticipated location the widget will be dropped to.
 * When the item is held over an empty area, the placeholder appears at the end of the grid if one
 * didn't exist already. As user moves the item over an existing item, the placeholder appears in
 * place of that existing item. And then, the existing item is pushed over as part of re-ordering.
 *
 * Once item is dropped, new ordering along with the dropped item is persisted. See
 * [ContentListState.onSaveList].
 *
 * Difference between this and [GridDragDropState] is that, this is used for listening to drops from
 * other activities. [GridDragDropState] on the other hand, handles dragging of existing items in
 * the communal hub grid.
 */
internal class DragAndDropTargetState(
    private val state: LazyGridState,
    private val contentOffset: Offset,
    private val contentListState: ContentListState,
    private val autoScrollThreshold: Float,
    private val scope: CoroutineScope,
) {
    /**
     * The placeholder item that is treated as if it is being dragged across the grid. It is added
     * to grid once drag and drop event is started and removed when event ends.
     */
    private var placeHolder = CommunalContentModel.WidgetPlaceholder()
    private var placeHolderIndex: Int? = null

    internal val scrollChannel = Channel<Float>()

    fun onStarted() {
        // assume item will be added to the end.
        contentListState.list.add(placeHolder)
        placeHolderIndex = contentListState.list.size - 1
    }

    fun onMoved(event: DragAndDropEvent) {
        val dragOffset = event.toOffset()

        val targetItem =
            state.layoutInfo.visibleItemsInfo
                .asSequence()
                .filter { item -> contentListState.isItemEditable(item.index) }
                .firstItemAtOffset(dragOffset - contentOffset)

        if (targetItem != null) {
            var scrollIndex: Int? = null
            var scrollOffset: Int? = null
            if (placeHolderIndex == state.firstVisibleItemIndex) {
                // Save info about the first item before the move, to neutralize the automatic
                // keeping first item first.
                scrollIndex = placeHolderIndex
                scrollOffset = state.firstVisibleItemScrollOffset
            }

            if (contentListState.isItemEditable(targetItem.index)) {
                movePlaceholderTo(targetItem.index)
                placeHolderIndex = targetItem.index
            }

            if (scrollIndex != null && scrollOffset != null) {
                // this is needed to neutralize automatic keeping the first item first.
                scope.launch { state.scrollToItem(scrollIndex, scrollOffset) }
            }
        } else {
            computeAutoscroll(dragOffset).takeIf { it != 0f }?.let { scrollChannel.trySend(it) }
        }
    }

    fun onDrop(event: DragAndDropEvent): Boolean {
        return placeHolderIndex?.let { dropIndex ->
            val widgetExtra = event.maybeWidgetExtra() ?: return false
            val (componentName, user) = widgetExtra
            if (componentName != null && user != null) {
                // Placeholder isn't removed yet to allow the setting the right rank for items
                // before adding in the new item.
                contentListState.onSaveList(
                    newItemComponentName = componentName,
                    newItemUser = user,
                    newItemIndex = dropIndex
                )
                return@let true
            }
            return false
        } ?: false
    }

    fun onEnded() {
        placeHolderIndex = null
        contentListState.list.remove(placeHolder)
    }

    fun onExited() {
        onEnded()
    }

    private fun computeAutoscroll(dragOffset: Offset): Float {
        val orientation = state.layoutInfo.orientation
        val distanceFromStart =
            if (orientation == Orientation.Horizontal) {
                dragOffset.x
            } else {
                dragOffset.y
            }
        val distanceFromEnd =
            if (orientation == Orientation.Horizontal) {
                state.layoutInfo.viewportEndOffset - dragOffset.x
            } else {
                state.layoutInfo.viewportEndOffset - dragOffset.y
            }

        return when {
            distanceFromEnd < autoScrollThreshold -> autoScrollThreshold - distanceFromEnd
            distanceFromStart < autoScrollThreshold -> distanceFromStart - autoScrollThreshold
            else -> 0f
        }
    }

    private fun movePlaceholderTo(index: Int) {
        val currentIndex = contentListState.list.indexOf(placeHolder)
        if (currentIndex != index) {
            contentListState.onMove(currentIndex, index)
        }
    }

    /**
     * Parses and returns the intent extra associated with the widget that is dropped into the grid.
     *
     * Returns null if the drop event didn't include intent information.
     */
    private fun DragAndDropEvent.maybeWidgetExtra(): WidgetPickerIntentUtils.WidgetExtra? {
        val clipData = this.toAndroidDragEvent().clipData.takeIf { it.itemCount != 0 }
        return clipData?.getItemAt(0)?.intent?.let { intent -> getWidgetExtraFromIntent(intent) }
    }

    private fun DragAndDropEvent.toOffset() = this.toAndroidDragEvent().run { Offset(x, y) }
}
