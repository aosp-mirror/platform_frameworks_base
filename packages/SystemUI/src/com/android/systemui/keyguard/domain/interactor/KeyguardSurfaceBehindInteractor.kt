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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardSurfaceBehindRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@SysUISingleton
class KeyguardSurfaceBehindInteractor
@Inject
constructor(
    private val repository: KeyguardSurfaceBehindRepository,
    private val fromLockscreenInteractor: FromLockscreenTransitionInteractor,
    private val fromPrimaryBouncerInteractor: FromPrimaryBouncerTransitionInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val viewParams: Flow<KeyguardSurfaceBehindModel> =
        transitionInteractor.isInTransitionToAnyState
            .flatMapLatest { isInTransition ->
                if (!isInTransition) {
                    defaultParams
                } else {
                    combine(
                        transitionSpecificViewParams,
                        defaultParams,
                    ) { transitionParams, defaultParams ->
                        transitionParams ?: defaultParams
                    }
                }
            }
            .distinctUntilChanged()

    val isAnimatingSurface = repository.isAnimatingSurface

    private val defaultParams =
        transitionInteractor.finishedKeyguardState.map { state ->
            KeyguardSurfaceBehindModel(
                alpha =
                    if (WindowManagerLockscreenVisibilityInteractor.isSurfaceVisible(state)) 1f
                    else 0f
            )
        }

    /**
     * View params provided by the transition interactor for the most recently STARTED transition.
     * This is used to run transition-specific animations on the surface.
     *
     * If null, there are no transition-specific view params needed for this transition and we will
     * use a reasonable default.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val transitionSpecificViewParams: Flow<KeyguardSurfaceBehindModel?> =
        transitionInteractor.startedKeyguardTransitionStep.flatMapLatest { startedStep ->
            when (startedStep.from) {
                KeyguardState.LOCKSCREEN -> fromLockscreenInteractor.surfaceBehindModel
                KeyguardState.PRIMARY_BOUNCER -> fromPrimaryBouncerInteractor.surfaceBehindModel
                // Return null for other states, where no transition specific params are needed.
                else -> flowOf(null)
            }
        }

    fun setAnimatingSurface(animating: Boolean) {
        repository.setAnimatingSurface(animating)
    }

    fun setSurfaceRemoteAnimationTargetAvailable(available: Boolean) {
        repository.setSurfaceRemoteAnimationTargetAvailable(available)
    }
}
