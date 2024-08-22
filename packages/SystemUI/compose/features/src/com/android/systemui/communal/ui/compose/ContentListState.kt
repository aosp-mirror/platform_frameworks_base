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
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.widgets.WidgetConfigurator

@Composable
fun rememberContentListState(
    widgetConfigurator: WidgetConfigurator?,
    communalContent: List<CommunalContentModel>,
    viewModel: BaseCommunalViewModel,
): ContentListState {
    return remember(communalContent) {
        ContentListState(
            communalContent,
            { componentName, user, rank ->
                viewModel.onAddWidget(
                    componentName,
                    user,
                    rank,
                    widgetConfigurator,
                )
            },
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
    private val onAddWidget: (componentName: ComponentName, user: UserHandle, rank: Int) -> Unit,
    private val onDeleteWidget: (id: Int, componentName: ComponentName, rank: Int) -> Unit,
    private val onReorderWidgets: (widgetIdToRankMap: Map<Int, Int>) -> Unit,
) {
    var list = communalContent.toMutableStateList()
        private set

    /** Move item to a new position in the list. */
    fun onMove(fromIndex: Int, toIndex: Int) {
        list.apply { add(toIndex, removeAt(fromIndex)) }
    }

    /** Remove widget from the list and the database. */
    fun onRemove(indexToRemove: Int) {
        if (list[indexToRemove].isWidgetContent()) {
            val widget = list[indexToRemove] as CommunalContentModel.WidgetContent
            list.apply { removeAt(indexToRemove) }
            onDeleteWidget(widget.appWidgetId, widget.componentName, widget.rank)
        }
    }

    /**
     * Persists the new order with all the movements happened during drag operations & the new
     * widget drop (if applicable).
     *
     * @param newItemComponentName name of the new widget that was dropped into the list; null if no
     *   new widget was added.
     * @param newItemUser user profile associated with the new widget that was dropped into the
     *   list; null if no new widget was added.
     * @param newItemIndex index at which the a new widget was dropped into the list; null if no new
     *   widget was dropped.
     */
    fun onSaveList(
        newItemComponentName: ComponentName? = null,
        newItemUser: UserHandle? = null,
        newItemIndex: Int? = null
    ) {
        // New widget added to the grid. Other widgets are shifted as needed at the database level.
        if (newItemComponentName != null && newItemUser != null && newItemIndex != null) {
            onAddWidget(newItemComponentName, newItemUser, /* rank= */ newItemIndex)
            return
        }

        // No new widget, only reorder existing widgets.
        val widgetIdToRankMap: Map<Int, Int> =
            list
                .mapIndexedNotNull { index, item ->
                    if (item is CommunalContentModel.WidgetContent) {
                        item.appWidgetId to index
                    } else {
                        null
                    }
                }
                .toMap()
        onReorderWidgets(widgetIdToRankMap)
    }

    /** Returns true if the item at given index is editable. */
    fun isItemEditable(index: Int) = list[index].isWidgetContent()
}
