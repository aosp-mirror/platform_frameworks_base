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
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.RemoteViews
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.shade.ShadeViewController
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** The base view model for the communal hub. */
abstract class BaseCommunalViewModel(
    private val communalInteractor: CommunalInteractor,
    private val shadeViewController: Provider<ShadeViewController>,
    private val powerManager: PowerManager,
    val mediaHost: MediaHost,
) {
    val isKeyguardVisible: Flow<Boolean> = communalInteractor.isKeyguardVisible

    val currentScene: StateFlow<CommunalSceneKey> = communalInteractor.desiredScene

    fun onSceneChanged(scene: CommunalSceneKey) {
        communalInteractor.onSceneChanged(scene)
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?) {
        communalInteractor.setTransitionState(transitionState)
    }

    /**
     * Called when a widget is added via drag and drop from the widget picker into the communal hub.
     */
    open fun onAddWidget(componentName: ComponentName, priority: Int) {
        communalInteractor.addWidget(componentName, priority, ::configureWidget)
    }

    /**
     * Called when a widget needs to be configured, with the id of the widget. The return value
     * should represent whether configuring the widget was successful.
     */
    protected open suspend fun configureWidget(widgetId: Int): Boolean {
        return true
    }

    // TODO(b/308813166): remove once CommunalContainer is moved lower in z-order and doesn't block
    //  touches anymore.
    /** Called when a touch is received outside the edge swipe area when hub mode is closed. */
    fun onOuterTouch(motionEvent: MotionEvent) {
        // Forward the touch to the shade so that basic gestures like swipe up/down for
        // shade/bouncer work.
        shadeViewController.get().handleExternalTouch(motionEvent)
    }

    // TODO(b/308813166): remove once CommunalContainer is moved lower in z-order and doesn't block
    //  touches anymore.
    /** Called to refresh the screen timeout when a user touch is received. */
    fun onUserActivity() {
        powerManager.userActivity(
            SystemClock.uptimeMillis(),
            PowerManager.USER_ACTIVITY_EVENT_TOUCH,
            0
        )
    }

    /** A list of all the communal content to be displayed in the communal hub. */
    abstract val communalContent: Flow<List<CommunalContentModel>>

    /** Whether in edit mode for the communal hub. */
    open val isEditMode = false

    /** Whether the popup message triggered by dismissing the CTA tile is showing. */
    open val isPopupOnDismissCtaShowing: Flow<Boolean> = flowOf(false)

    /** Hide the popup message triggered by dismissing the CTA tile. */
    open fun onHidePopupAfterDismissCta() {}

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

    /** Called as the UI requests opening the widget editor. */
    open fun onOpenWidgetEditor() {}

    /** Called as the UI requests to dismiss the CTA tile. */
    open fun onDismissCtaTile() {}

    /** Gets the interaction handler used to handle taps on a remote view */
    abstract fun getInteractionHandler(): RemoteViews.InteractionHandler

    /** Called as the user starts dragging a widget to reorder. */
    open fun onReorderWidgetStart() {}

    /** Called as the user finishes dragging a widget to reorder. */
    open fun onReorderWidgetEnd() {}

    /** Called as the user cancels dragging a widget to reorder. */
    open fun onReorderWidgetCancel() {}
}
