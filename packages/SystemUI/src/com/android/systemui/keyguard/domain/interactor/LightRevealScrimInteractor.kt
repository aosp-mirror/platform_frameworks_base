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

import com.android.keyguard.logging.ScrimLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.LightRevealScrimRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
@SysUISingleton
class LightRevealScrimInteractor
@Inject
constructor(
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val lightRevealScrimRepository: LightRevealScrimRepository,
    @Application private val scope: CoroutineScope,
    private val scrimLogger: ScrimLogger,
) {

    init {
        listenForStartedKeyguardTransitionStep()
    }

    private fun listenForStartedKeyguardTransitionStep() {
        scope.launch {
            transitionInteractor.startedKeyguardTransitionStep.collect {
                scrimLogger.d(TAG, "listenForStartedKeyguardTransitionStep", it)
                if (willTransitionChangeEndState(it)) {
                    lightRevealScrimRepository.startRevealAmountAnimator(
                        willBeRevealedInState(it.to)
                    )
                }
            }
        }
    }

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

    val revealAmount = lightRevealScrimRepository.revealAmount

    companion object {

        /**
         * Whether the transition requires a change in the reveal amount of the light reveal scrim.
         * If not, we don't care about the transition and don't need to listen to it.
         */
        fun willTransitionChangeEndState(transition: TransitionStep): Boolean {
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
                KeyguardState.DREAMING_LOCKSCREEN_HOSTED -> true
                KeyguardState.ALTERNATE_BOUNCER -> true
                KeyguardState.PRIMARY_BOUNCER -> true
                KeyguardState.LOCKSCREEN -> true
                KeyguardState.GONE -> true
                KeyguardState.OCCLUDED -> true
            }
        }

        val TAG = LightRevealScrimInteractor::class.simpleName!!
    }
}
