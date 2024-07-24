/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.bouncer.ui.viewmodel

import android.view.View
import com.android.systemui.biometrics.shared.SideFpsControllerRefactor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.model.BouncerShowMessageModel
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.bouncer.ui.BouncerViewDelegate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Models UI state for the lock screen bouncer; handles user input. */
@ExperimentalCoroutinesApi
class KeyguardBouncerViewModel
@Inject
constructor(
    private val view: BouncerView,
    private val interactor: PrimaryBouncerInteractor,
) {
    /** Observe on bouncer expansion amount. */
    val bouncerExpansionAmount: Flow<Float> = interactor.panelExpansionAmount

    /** Can the user interact with the view? */
    val isInteractable: Flow<Boolean> = interactor.isInteractable

    /** Observe whether bouncer is showing or not. */
    val isShowing: Flow<Boolean> = interactor.isShowing

    /** Observe whether bouncer is starting to hide. */
    val startingToHide: Flow<Unit> = interactor.startingToHide

    /** Observe whether we want to start the disappear animation. */
    val startDisappearAnimation: Flow<Runnable> = interactor.startingDisappearAnimation

    /** Observe whether we want to update keyguard position. */
    val keyguardPosition: Flow<Float> = interactor.keyguardPosition

    /** Observe whether we want to update resources. */
    val updateResources: Flow<Boolean> = interactor.resourceUpdateRequests

    /** Observe whether we want to set a keyguard message when the bouncer shows. */
    val bouncerShowMessage: Flow<BouncerShowMessageModel> = interactor.showMessage

    /** Observe whether keyguard is authenticated already. */
    val keyguardAuthenticated: Flow<Boolean> = interactor.keyguardAuthenticatedBiometrics

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    /** Observe whether the side fps is showing. */
    val sideFpsShowing: Flow<Boolean> = interactor.sideFpsShowing

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    /** Observe whether we should update fps is showing. */
    val shouldUpdateSideFps: Flow<Unit> =
        merge(
            interactor.isShowing.map {},
            interactor.startingToHide,
            interactor.startingDisappearAnimation.filterNotNull().map {}
        )

    /** Observe whether we want to update resources. */
    fun notifyUpdateResources() {
        interactor.notifyUpdatedResources()
    }

    /** Notify that keyguard authenticated was handled */
    fun notifyKeyguardAuthenticated() {
        interactor.notifyKeyguardAuthenticatedHandled()
    }

    /** Notifies that the message was shown. */
    fun onMessageShown() {
        interactor.onMessageShown()
    }

    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
    fun updateSideFpsVisibility() {
        SideFpsControllerRefactor.assertInLegacyMode()
        interactor.updateSideFpsVisibility()
    }

    /** Observe whether back button is enabled. */
    fun observeOnIsBackButtonEnabled(systemUiVisibility: () -> Int): Flow<Int> {
        return interactor.isBackButtonEnabled.map { enabled ->
            var vis: Int = systemUiVisibility()
            vis =
                if (enabled) {
                    vis and View.STATUS_BAR_DISABLE_BACK.inv()
                } else {
                    vis or View.STATUS_BAR_DISABLE_BACK
                }
            vis
        }
    }

    /** Set an abstraction that will hold reference to the ui delegate for the bouncer view. */
    fun setBouncerViewDelegate(delegate: BouncerViewDelegate?) {
        view.delegate = delegate
    }
}
