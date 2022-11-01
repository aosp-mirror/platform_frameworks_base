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

package com.android.systemui.keyguard.ui.viewmodel

import android.view.View
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.BouncerViewDelegate
import com.android.systemui.keyguard.domain.interactor.BouncerInteractor
import com.android.systemui.keyguard.shared.model.BouncerCallbackActionsModel
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.keyguard.shared.model.KeyguardBouncerModel
import com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_VISIBLE
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Models UI state for the lock screen bouncer; handles user input. */
class KeyguardBouncerViewModel
@Inject
constructor(
    private val view: BouncerView,
    private val interactor: BouncerInteractor,
) {
    /** Observe on bouncer expansion amount. */
    val bouncerExpansionAmount: Flow<Float> = interactor.expansionAmount

    /** Observe on bouncer visibility. */
    val isBouncerVisible: Flow<Boolean> = interactor.isVisible

    /** Observe whether bouncer is showing. */
    val show: Flow<KeyguardBouncerModel> = interactor.show

    /** Observe bouncer prompt when bouncer is showing. */
    val showPromptReason: Flow<Int> = interactor.show.map { it.promptReason }

    /** Observe bouncer error message when bouncer is showing. */
    val showBouncerErrorMessage: Flow<CharSequence> =
        interactor.show.map { it.errorMessage }.filterNotNull()

    /** Observe visible expansion when bouncer is showing. */
    val showWithFullExpansion: Flow<KeyguardBouncerModel> =
        interactor.show.filter { it.expansionAmount == EXPANSION_VISIBLE }

    /** Observe whether bouncer is hiding. */
    val hide: Flow<Unit> = interactor.hide

    /** Observe whether bouncer is starting to hide. */
    val startingToHide: Flow<Unit> = interactor.startingToHide

    /** Observe whether we want to set the dismiss action to the bouncer. */
    val setDismissAction: Flow<BouncerCallbackActionsModel> = interactor.onDismissAction

    /** Observe whether we want to start the disappear animation. */
    val startDisappearAnimation: Flow<Runnable> = interactor.startingDisappearAnimation

    /** Observe whether we want to update keyguard position. */
    val keyguardPosition: Flow<Float> = interactor.keyguardPosition

    /** Observe whether we want to update resources. */
    val updateResources: Flow<Boolean> = interactor.resourceUpdateRequests

    /** Observe whether we want to set a keyguard message when the bouncer shows. */
    val bouncerShowMessage: Flow<BouncerShowMessageModel> = interactor.showMessage

    /** Observe whether keyguard is authenticated already. */
    val keyguardAuthenticated: Flow<Boolean> = interactor.keyguardAuthenticated

    /** Observe whether screen is turned off. */
    val screenTurnedOff: Flow<Unit> = interactor.screenTurnedOff

    /** Notify that view visibility has changed. */
    fun notifyBouncerVisibilityHasChanged(visibility: Int) {
        return interactor.notifyBouncerVisibilityHasChanged(visibility)
    }
    /** Observe whether we want to update resources. */
    fun notifyUpdateResources() {
        interactor.notifyUpdatedResources()
    }

    /** Notify that keyguard authenticated was handled */
    fun notifyKeyguardAuthenticated() {
        interactor.notifyKeyguardAuthenticatedHandled()
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
