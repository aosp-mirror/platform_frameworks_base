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

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.UserHandle
import android.view.View
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** The base view model for the communal hub. */
abstract class BaseCommunalViewModel(
    val communalSceneInteractor: CommunalSceneInteractor,
    private val communalInteractor: CommunalInteractor,
    val mediaHost: MediaHost,
) {
    val currentScene: Flow<SceneKey> = communalSceneInteractor.currentScene

    /** Used to animate showing or hiding the communal content. */
    open val isCommunalContentVisible: Flow<Boolean> = MutableStateFlow(false)

    /** Whether communal hub should be focused by accessibility tools. */
    open val isFocusable: Flow<Boolean> = MutableStateFlow(false)

    /** Whether widgets are currently being re-ordered. */
    open val reorderingWidgets: StateFlow<Boolean> = MutableStateFlow(false)

    /** The key of the currently selected item, or null if no item selected. */
    val selectedKey: StateFlow<String?> = communalInteractor.selectedKey

    private val _isTouchConsumed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Whether an element inside the lazy grid is actively consuming touches */
    val isTouchConsumed: Flow<Boolean> = _isTouchConsumed.asStateFlow()

    private val _isNestedScrolling: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Whether the lazy grid is reporting scrolling within itself */
    val isNestedScrolling: Flow<Boolean> = _isNestedScrolling.asStateFlow()

    /**
     * Whether touch is available to be consumed by a touch handler. Touch is available during
     * nested scrolling as lazy grid reports this for all scroll directions that it detects. In the
     * case that there is consumed scrolling on a nested element, such as an AndroidView, no nested
     * scrolling will be reported. It is up to the flow consumer to determine whether the nested
     * scroll can be applied. In the communal case, this would be identifying the scroll as
     * vertical, which the lazy horizontal grid does not handle.
     */
    val glanceableTouchAvailable: Flow<Boolean> = anyOf(not(isTouchConsumed), isNestedScrolling)

    /** Accessibility delegate to be set on CommunalAppWidgetHostView. */
    open val widgetAccessibilityDelegate: View.AccessibilityDelegate? = null

    /**
     * The up-to-date value of the grid scroll offset. persisted to interactor on
     * {@link #persistScrollPosition}
     */
    private var currentScrollOffset = 0

    /**
     * The up-to-date value of the grid scroll index. persisted to interactor on
     * {@link #persistScrollPosition}
     */
    private var currentScrollIndex = 0

    fun signalUserInteraction() {
        communalInteractor.signalUserInteraction()
    }

    /**
     * Asks for an asynchronous scene witch to [newScene], which will use the corresponding
     * installed transition or the one specified by [transitionKey], if provided.
     */
    fun changeScene(
        scene: SceneKey,
        loggingReason: String,
        transitionKey: TransitionKey? = null,
        keyguardState: KeyguardState? = null
    ) {
        communalSceneInteractor.changeScene(scene, loggingReason, transitionKey, keyguardState)
    }

    fun setEditModeState(state: EditModeState?) = communalSceneInteractor.setEditModeState(state)

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        communalSceneInteractor.setTransitionState(transitionState)
    }

    open fun onOpenEnableWidgetDialog() {}

    open fun onOpenEnableWorkProfileDialog() {}

    /** A list of all the communal content to be displayed in the communal hub. */
    abstract val communalContent: Flow<List<CommunalContentModel>>

    /**
     * Whether to freeze the emission of the communalContent flow to prevent recomposition. Defaults
     * to false, indicating that the flow will emit new update.
     */
    open val isCommunalContentFlowFrozen: Flow<Boolean> = flowOf(false)

    /** Whether in edit mode for the communal hub. */
    open val isEditMode = false

    /** Whether the type of popup currently showing */
    open val currentPopup: Flow<PopupType?> = flowOf(null)

    /** Whether the communal hub is empty with no widget available. */
    open val isEmptyState: Flow<Boolean> = flowOf(false)

    /** Called as the UI request to dismiss the any displaying popup */
    open fun onHidePopup() {}

    /** Called as the UI requests adding a widget. */
    open fun onAddWidget(
        componentName: ComponentName,
        user: UserHandle,
        rank: Int? = null,
        configurator: WidgetConfigurator? = null,
    ) {}

    /** Called as the UI requests deleting a widget. */
    open fun onDeleteWidget(
        id: Int,
        componentName: ComponentName,
        rank: Int,
    ) {}

    /** Called as the UI detects a tap event on the widget. */
    open fun onTapWidget(
        componentName: ComponentName,
        rank: Int,
    ) {}

    /**
     * Called as the UI requests reordering widgets.
     *
     * @param widgetIdToRankMap mapping of the widget ids to its rank. When re-ordering to add a new
     *   item in the middle, provide the priorities of existing widgets as if the new item existed,
     *   and then, call [onAddWidget] to add the new item at intended order.
     */
    open fun onReorderWidgets(widgetIdToRankMap: Map<Int, Int>) {}

    /** Called as the UI requests opening the widget editor with an optional preselected widget. */
    open fun onOpenWidgetEditor(
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
    open fun onLongClick() {}

    /** Called as the UI determines that a new widget has been added to the grid. */
    open fun onNewWidgetAdded(provider: AppWidgetProviderInfo) {}

    /** Called when the grid scroll position has been updated. */
    open fun onScrollPositionUpdated(firstVisibleItemIndex: Int, firstVisibleItemScroll: Int) {
        currentScrollIndex = firstVisibleItemIndex
        currentScrollOffset = firstVisibleItemScroll
    }

    /** Stores scroll values to interactor. */
    protected fun persistScrollPosition() {
        communalInteractor.setScrollPosition(currentScrollIndex, currentScrollOffset)
    }

    /** Invoked after scroll values are used to initialize grid position. */
    open fun clearPersistedScrollPosition() {
        communalInteractor.setScrollPosition(0, 0)
    }

    val savedFirstScrollIndex: Int
        get() = communalInteractor.firstVisibleItemIndex

    val savedFirstScrollOffset: Int
        get() = communalInteractor.firstVisibleItemOffset

    /** Set the key of the currently selected item */
    fun setSelectedKey(key: String?) {
        communalInteractor.setSelectedKey(key)
    }

    /** Invoked once touches inside the lazy grid are consumed */
    fun onHubTouchConsumed() {
        if (_isTouchConsumed.value) {
            return
        }

        _isTouchConsumed.value = true
    }

    /** Invoked when nested scrolling begins on the lazy grid */
    fun onNestedScrolling() {
        if (_isNestedScrolling.value) {
            return
        }

        _isNestedScrolling.value = true
    }

    /** Resets nested scroll and touch consumption state */
    fun onResetTouchState() {
        _isTouchConsumed.value = false
        _isNestedScrolling.value = false
    }
}
