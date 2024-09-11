/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** View model for accessibility actions placeholder on keyguard */
class AccessibilityActionsViewModel
@Inject
constructor(
    private val communalInteractor: CommunalInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    val isCommunalAvailable = communalInteractor.isCommunalAvailable

    // Checks that we are fully in lockscreen, not transitioning to another state, and shade is not
    // opened.
    val isOnKeyguard =
        combine(
                keyguardTransitionInteractor.transitionValue(KeyguardState.LOCKSCREEN).map {
                    it == 1f
                },
                keyguardInteractor.statusBarState
            ) { transitionFinishedOnLockscreen, statusBarState ->
                transitionFinishedOnLockscreen && statusBarState == StatusBarState.KEYGUARD
            }
            .distinctUntilChanged()

    fun openCommunalHub() = communalInteractor.changeScene(CommunalScenes.Communal)
}
