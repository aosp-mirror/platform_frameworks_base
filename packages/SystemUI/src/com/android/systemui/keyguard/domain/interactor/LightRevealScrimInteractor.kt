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
 * limitations under the License.
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.LightRevealScrimRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@ExperimentalCoroutinesApi
@SysUISingleton
class LightRevealScrimInteractor
@Inject
constructor(
    transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    lightRevealScrimRepository: LightRevealScrimRepository,
) {

    /**
     * Whenever a keyguard transition starts, sample the latest reveal effect from the repository
     * and use that for the starting transition.
     *
     * We can't simply use the nextRevealEffect since the effect may change midway through a
     * transition, but we don't want to change effects part way through. For example, if we're using
     * a CircleReveal to animate a biometric unlock, but the biometric unlock mode changes to NONE
     * from WAKE_AND_UNLOCK before the unlock animation ends, we don't want to end up switching to a
     * LiftReveal.
     */
    val lightRevealEffect: Flow<LightRevealEffect> =
        transitionInteractor.startedKeyguardTransitionStep.sample(
            lightRevealScrimRepository.revealEffect
        )

    /**
     * The reveal amount to use for the light reveal scrim, which is derived from the keyguard
     * transition steps.
     */
    val revealAmount: Flow<Float> =
        transitionRepository.transitions
            // Only listen to transitions that change the reveal amount.
            .filter { willTransitionAffectRevealAmount(it) }
            // Use the transition amount as the reveal amount, inverting it if we're transitioning
            // to a non-revealed (hidden) state.
            .map { step -> if (willBeRevealedInState(step.to)) step.value else 1f - step.value }

    companion object {

        /**
         * Whether the transition requires a change in the reveal amount of the light reveal scrim.
         * If not, we don't care about the transition and don't need to listen to it.
         */
        fun willTransitionAffectRevealAmount(transition: TransitionStep): Boolean {
            return willBeRevealedInState(transition.from) != willBeRevealedInState(transition.to)
        }

        /**
         * Whether the light reveal scrim will be fully revealed (revealAmount = 1.0f) in the given
         * state after the transition is complete. If false, scrim will be fully hidden.
         */
        fun willBeRevealedInState(state: KeyguardState): Boolean {
            return when (state) {
                KeyguardState.OFF -> false
                KeyguardState.DOZING -> false
                KeyguardState.AOD -> false
                KeyguardState.DREAMING -> true
                KeyguardState.BOUNCER -> true
                KeyguardState.LOCKSCREEN -> true
                KeyguardState.GONE -> true
                KeyguardState.OCCLUDED -> true
            }
        }
    }
}
