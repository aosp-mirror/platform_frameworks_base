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
 * limitations under the License.
 */

package com.android.systemui.keyguard.domain.interactor

import android.annotation.FloatRange
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * This interactor provides direct access to [KeyguardTransitionRepository] internals and exposes
 * functions to directly modify the transition state.
 */
@SysUISingleton
class InternalKeyguardTransitionInteractor
@Inject
constructor(
    private val repository: KeyguardTransitionRepository,
) {

    /**
     * The [TransitionInfo] of the most recent call to
     * [KeyguardTransitionRepository.startTransition].
     *
     * This should only be used by keyguard transition internals (From*TransitionInteractor and
     * related classes). Other consumers of keyguard state in System UI should use
     * [startedKeyguardState], [currentKeyguardState], and related flows.
     *
     * Keyguard internals use this to determine the most up-to-date KeyguardState that we've
     * requested a transition to, even if the animator running the transition on the main thread has
     * not yet emitted the STARTED TransitionStep.
     *
     * For example: if we're finished in GONE and press the power button twice very quickly, we may
     * request a transition to AOD, but then receive the second power button press prior to the
     * STARTED -> AOD transition step emitting. We still need the FromAodTransitionInteractor to
     * request a transition from AOD -> LOCKSCREEN in response to the power press, even though the
     * main thread animator hasn't emitted STARTED > AOD yet (which means [startedKeyguardState] is
     * still GONE, which is not relevant to FromAodTransitionInteractor). In this case, the
     * interactor can use this current transition info to determine that a STARTED -> AOD step
     * *will* be emitted, and therefore that it can safely request an AOD -> LOCKSCREEN transition
     * which will subsequently cancel GONE -> AOD.
     */
    internal val currentTransitionInfoInternal: StateFlow<TransitionInfo> =
        repository.currentTransitionInfoInternal

    suspend fun startTransition(info: TransitionInfo) = repository.startTransition(info)

    suspend fun updateTransition(
        transitionId: UUID,
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        state: TransitionState
    ) = repository.updateTransition(transitionId, value, state)
}
