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

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@SysUISingleton
class FromAodTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.AOD,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {

    override fun start() {
        listenForAodToLockscreen()
        listenForAodToPrimaryBouncer()
        listenForAodToGone()
        listenForAodToOccluded()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    /**
     * There are cases where the transition to AOD begins but never completes, such as tapping power
     * during an incoming phone call when unlocked. In this case, GONE->AOD should be interrupted to
     * run AOD->OCCLUDED.
     */
    private fun listenForAodToOccluded() {
        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isOccluded, lastStartedStep) ->
                    if (isOccluded && lastStartedStep.to == KeyguardState.AOD) {
                        startTransitionTo(
                            toState = KeyguardState.OCCLUDED,
                            modeOnCanceled = TransitionModeOnCanceled.RESET
                        )
                    }
                }
        }
    }

    private fun listenForAodToLockscreen() {
        scope.launch {
            keyguardInteractor
                .dozeTransitionTo(DozeStateModel.FINISH)
                .sample(
                    keyguardInteractor.isKeyguardShowing,
                    startedKeyguardTransitionStep,
                    keyguardInteractor.isKeyguardOccluded,
                    keyguardInteractor.biometricUnlockState,
                    keyguardInteractor.primaryBouncerShowing,
                )
                .collect {
                    (
                        _,
                        isKeyguardShowing,
                        lastStartedStep,
                        occluded,
                        biometricUnlockState,
                        primaryBouncerShowing) ->
                    if (
                        lastStartedStep.to == KeyguardState.AOD &&
                            !occluded &&
                            !isWakeAndUnlock(biometricUnlockState) &&
                            isKeyguardShowing &&
                            !primaryBouncerShowing
                    ) {
                        val modeOnCanceled =
                            if (lastStartedStep.from == KeyguardState.LOCKSCREEN) {
                                TransitionModeOnCanceled.REVERSE
                            } else if (lastStartedStep.from == KeyguardState.GONE) {
                                TransitionModeOnCanceled.RESET
                            } else {
                                TransitionModeOnCanceled.LAST_VALUE
                            }
                        startTransitionTo(
                            toState = KeyguardState.LOCKSCREEN,
                            modeOnCanceled = modeOnCanceled,
                        )
                    }
                }
        }
    }

    /**
     * If there is a biometric lockout and FPS is tapped while on AOD, it should go directly to the
     * PRIMARY_BOUNCER.
     */
    private fun listenForAodToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isBouncerShowing, lastStartedTransitionStep) ->
                    if (isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.AOD) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    private fun listenForAodToGone() {
        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            powerInteractor.isAwake
                .debounce(50L)
                .sample(
                    keyguardInteractor.biometricUnlockState,
                    startedKeyguardTransitionStep,
                    keyguardInteractor.isKeyguardShowing,
                    keyguardInteractor.isKeyguardDismissible,
                )
                .collect {
                    (
                        isAwake,
                        biometricUnlockState,
                        lastStartedTransitionStep,
                        isKeyguardShowing,
                        isKeyguardDismissible) ->
                    KeyguardWmStateRefactor.assertInLegacyMode()
                    if (
                        isAwake &&
                            lastStartedTransitionStep.to == KeyguardState.AOD &&
                            (isWakeAndUnlock(biometricUnlockState) ||
                                (!isKeyguardShowing && isKeyguardDismissible))
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    /**
     * Dismisses AOD and transitions to GONE. This is called whenever authentication occurs while on
     * AOD.
     */
    fun dismissAod() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromAodTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 500.milliseconds
        val TO_GONE_DURATION = DEFAULT_DURATION
        val TO_OCCLUDED_DURATION = DEFAULT_DURATION
    }
}
