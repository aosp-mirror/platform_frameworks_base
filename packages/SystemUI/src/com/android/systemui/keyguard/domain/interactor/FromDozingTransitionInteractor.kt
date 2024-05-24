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
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@SysUISingleton
class FromDozingTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
    private val communalInteractor: CommunalInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.DOZING,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {

    override fun start() {
        listenForDozingToAny()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private val canDismissLockScreen: Flow<Boolean> =
        combine(
            keyguardInteractor.isKeyguardShowing,
            keyguardInteractor.isKeyguardDismissible,
        ) { isKeyguardShowing, isKeyguardDismissible ->
            isKeyguardDismissible && !isKeyguardShowing
        }

    private fun listenForDozingToAny() {
        scope.launch {
            powerInteractor.isAwake
                .debounce(50L)
                .sample(
                    keyguardInteractor.biometricUnlockState,
                    startedKeyguardTransitionStep,
                    keyguardInteractor.isKeyguardOccluded,
                    communalInteractor.isIdleOnCommunal,
                    canDismissLockScreen,
                    keyguardInteractor.primaryBouncerShowing,
                )
                .collect {
                    (
                        isAwake,
                        biometricUnlockState,
                        lastStartedTransition,
                        occluded,
                        isIdleOnCommunal,
                        canDismissLockScreen,
                        primaryBouncerShowing) ->
                    if (!(isAwake && lastStartedTransition.to == KeyguardState.DOZING)) {
                        return@collect
                    }
                    startTransitionTo(
                        if (isWakeAndUnlock(biometricUnlockState)) {
                            KeyguardState.GONE
                        } else if (canDismissLockScreen) {
                            KeyguardState.GONE
                        } else if (primaryBouncerShowing) {
                            KeyguardState.PRIMARY_BOUNCER
                        } else if (occluded) {
                            KeyguardState.OCCLUDED
                        } else if (isIdleOnCommunal) {
                            KeyguardState.GLANCEABLE_HUB
                        } else {
                            KeyguardState.LOCKSCREEN
                        }
                    )
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration = DEFAULT_DURATION.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromDozingTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = DEFAULT_DURATION
        val TO_GONE_DURATION = DEFAULT_DURATION
        val TO_PRIMARY_BOUNCER_DURATION = DEFAULT_DURATION
    }
}
