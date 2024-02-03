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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge

/** Models UI state for the alpha of the AOD (always-on display). */
@SysUISingleton
class AodAlphaViewModel
@Inject
constructor(
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
) {

    /** The alpha level for the entire lockscreen while in AOD. */
    val alpha: Flow<Float> =
        combine(
                keyguardTransitionInteractor.currentKeyguardState,
                merge(
                    keyguardInteractor.keyguardAlpha,
                    occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                )
            ) { currentKeyguardState, alpha ->
                if (currentKeyguardState == KeyguardState.GONE) {
                    // Ensures content is not visible when in GONE state
                    0f
                } else {
                    alpha
                }
            }
            .distinctUntilChanged()
}
