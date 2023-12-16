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

import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.shade.ShadeViewController
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

    /** Called as the UI requests deleting a widget. */
    open fun onDeleteWidget(id: Int) {}

    /** Called as the UI requests reordering widgets. */
    open fun onReorderWidgets(ids: List<Int>) {}

    /** Called as the UI requests opening the widget editor. */
    open fun onOpenWidgetEditor() {}
}
