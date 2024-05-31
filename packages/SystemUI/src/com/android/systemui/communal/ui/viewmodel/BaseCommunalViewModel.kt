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

package com.android.systemui.communal.ui.viewmodel

import android.content.ComponentName
import android.os.UserHandle
import android.view.View
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.media.controls.ui.view.MediaHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** The base view model for the communal hub. */
abstract class BaseCommunalViewModel(
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val communalInteractor: CommunalInteractor,
    val mediaHost: MediaHost,
) {
    val currentScene: Flow<SceneKey> = communalSceneInteractor.currentScene

    /** Whether communal hub should be focused by accessibility tools. */
    open val isFocusable: Flow<Boolean> = MutableStateFlow(false)

    /** Whether widgets are currently being re-ordered. */
    open val reorderingWidgets: StateFlow<Boolean> = MutableStateFlow(false)

    private val _selectedKey: MutableStateFlow<String?> = MutableStateFlow(null)

    /** The key of the currently selected item, or null if no item selected. */
    val selectedKey: StateFlow<String?>
        get() = _selectedKey

    /** Accessibility delegate to be set on CommunalAppWidgetHostView. */
    open val widgetAccessibilityDelegate: View.AccessibilityDelegate? = null

    fun signalUserInteraction() {
        communalInteractor.signalUserInteraction()
    }

    fun changeScene(scene: SceneKey, transitionKey: TransitionKey? = null) {
        communalSceneInteractor.changeScene(scene, transitionKey)
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        communalSceneInteractor.setTransitionState(transitionState)
    }

    /**
     * Called when a widget is added via drag and drop from the widget picker into the communal hub.
     */
    open fun onAddWidget(
        componentName: ComponentName,
        user: UserHandle,
        priority: Int,
        configurator: WidgetConfigurator? = null
    ) {
        communalInteractor.addWidget(componentName, user, priority, configurator)
    }

    open fun onOpenEnableWidgetDialog() {}

    open fun onOpenEnableWorkProfileDialog() {}

    /** A list of all the communal content to be displayed in the communal hub. */
    abstract val communalContent: Flow<List<CommunalContentModel>>

    /** Whether in edit mode for the communal hub. */
    open val isEditMode = false

    /** Whether the type of popup currently showing */
    open val currentPopup: Flow<PopupType?> = flowOf(null)

    /** Whether the communal hub is empty with no widget available. */
    open val isEmptyState: Flow<Boolean> = flowOf(false)

    /** Called as the UI request to dismiss the any displaying popup */
    open fun onHidePopup() {}

    /** Called as the UI requests deleting a widget. */
    open fun onDeleteWidget(id: Int) {}

    /**
     * Called as the UI requests reordering widgets.
     *
     * @param widgetIdToPriorityMap mapping of the widget ids to its priority. When re-ordering to
     *   add a new item in the middle, provide the priorities of existing widgets as if the new item
     *   existed, and then, call [onAddWidget] to add the new item at intended order.
     */
    open fun onReorderWidgets(widgetIdToPriorityMap: Map<Int, Int>) {}

    /** Called as the UI requests opening the widget editor with an optional preselected widget. */
    open fun onOpenWidgetEditor(
        preselectedKey: String? = null,
        shouldOpenWidgetPickerOnStart: Boolean = false,
    ) {}

    /** Called as the UI requests to dismiss the CTA tile. */
    open fun onDismissCtaTile() {}

    /** Called as the user starts dragging a widget to reorder. */
    open fun onReorderWidgetStart() {}

    /** Called as the user finishes dragging a widget to reorder. */
    open fun onReorderWidgetEnd() {}

    /** Called as the user cancels dragging a widget to reorder. */
    open fun onReorderWidgetCancel() {}

    /** Called as the user request to show the customize widget button. */
    open fun onShowCustomizeWidgetButton() {}

    /** Set the key of the currently selected item */
    fun setSelectedKey(key: String?) {
        _selectedKey.value = key
    }
}
