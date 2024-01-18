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

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel

@Composable
fun rememberContentListState(
    communalContent: List<CommunalContentModel>,
    viewModel: BaseCommunalViewModel,
): ContentListState {
    return remember(communalContent) {
        ContentListState(
            communalContent,
            viewModel::onAddWidget,
            viewModel::onDeleteWidget,
            viewModel::onReorderWidgets,
        )
    }
}

/**
 * Keeps the current state of the [CommunalContentModel] list being edited. [GridDragDropState]
 * interacts with this class to update the order in the list. [onSaveList] should be called on
 * dragging ends to persist the state in db for better performance.
 */
class ContentListState
internal constructor(
    communalContent: List<CommunalContentModel>,
    private val onAddWidget: (componentName: ComponentName, priority: Int) -> Unit,
    private val onDeleteWidget: (id: Int) -> Unit,
    private val onReorderWidgets: (widgetIdToPriorityMap: Map<Int, Int>) -> Unit,
) {
    var list = communalContent.toMutableStateList()
        private set

    /** Move item to a new position in the list. */
    fun onMove(fromIndex: Int, toIndex: Int) {
        list.apply { add(toIndex, removeAt(fromIndex)) }
    }

    /** Remove widget from the list and the database. */
    fun onRemove(indexToRemove: Int) {
        if (list[indexToRemove] is CommunalContentModel.Widget) {
            val widget = list[indexToRemove] as CommunalContentModel.Widget
            list.apply { removeAt(indexToRemove) }
            onDeleteWidget(widget.appWidgetId)
        }
    }

    /**
     * Persists the new order with all the movements happened during drag operations & the new
     * widget drop (if applicable).
     *
     * @param newItemComponentName name of the new widget that was dropped into the list; null if no
     *   new widget was added.
     * @param newItemIndex index at which the a new widget was dropped into the list; null if no new
     *   widget was dropped.
     */
    fun onSaveList(newItemComponentName: ComponentName? = null, newItemIndex: Int? = null) {
        // filters placeholder, but, maintains the indices of the widgets as if the placeholder was
        // in the list. When persisted in DB, this leaves space for the new item (to be added) at
        // the correct priority.
        val widgetIdToPriorityMap: Map<Int, Int> =
            list
                .mapIndexedNotNull { index, item ->
                    if (item is CommunalContentModel.Widget) {
                        item.appWidgetId to list.size - index
                    } else {
                        null
                    }
                }
                .toMap()
        // reorder and then add the new widget
        onReorderWidgets(widgetIdToPriorityMap)
        if (newItemComponentName != null && newItemIndex != null) {
            onAddWidget(newItemComponentName, /*priority=*/ list.size - newItemIndex)
        }
    }

    /** Returns true if the item at given index is editable. */
    fun isItemEditable(index: Int) = list[index] is CommunalContentModel.Widget
}
