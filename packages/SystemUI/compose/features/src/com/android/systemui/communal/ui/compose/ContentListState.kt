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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel

@Composable
fun rememberContentListState(
    communalContent: List<CommunalContentModel>,
    viewModel: CommunalEditModeViewModel,
): ContentListState {
    return remember(communalContent) {
        ContentListState(
            communalContent,
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
    private val onDeleteWidget: (id: Int) -> Unit,
    private val onReorderWidgets: (ids: List<Int>) -> Unit,
) {
    var list by mutableStateOf(communalContent)
        private set

    /** Move item to a new position in the list. */
    fun onMove(fromIndex: Int, toIndex: Int) {
        list = list.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    /** Remove widget from the list and the database. */
    fun onRemove(indexToRemove: Int) {
        if (list[indexToRemove] is CommunalContentModel.Widget) {
            val widget = list[indexToRemove] as CommunalContentModel.Widget
            list = list.toMutableList().apply { removeAt(indexToRemove) }
            onDeleteWidget(widget.appWidgetId)
        }
    }

    /** Persist the new order with all the movements happened during dragging. */
    fun onSaveList() {
        val widgetIds: List<Int> =
            list.filterIsInstance<CommunalContentModel.Widget>().map { it.appWidgetId }
        onReorderWidgets(widgetIds)
    }
}
