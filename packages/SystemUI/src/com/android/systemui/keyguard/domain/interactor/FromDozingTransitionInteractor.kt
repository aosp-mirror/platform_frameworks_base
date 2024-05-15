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
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode.Companion.isWakeAndUnlock
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
    powerInteractor: PowerInteractor,
    private val communalInteractor: CommunalInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    val deviceEntryRepository: DeviceEntryRepository,
) :
    TransitionInteractor(
        fromState = KeyguardState.DOZING,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
    ) {

    override fun start() {
        listenForDozingToAny()
        listenForWakeFromDozing()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private val canTransitionToGoneOnWake: Flow<Boolean> =
        combine(
            keyguardInteractor.isKeyguardShowing,
            keyguardInteractor.isKeyguardDismissible,
        ) { isKeyguardShowing, isKeyguardDismissible ->
            isKeyguardDismissible && !isKeyguardShowing
        }

    private fun listenForDozingToAny() {
        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            powerInteractor.isAwake
                .debounce(50L)
                .filterRelevantKeyguardStateAnd { isAwake -> isAwake }
                .sample(
                    keyguardInteractor.biometricUnlockState,
                    keyguardInteractor.isKeyguardOccluded,
                    communalInteractor.isIdleOnCommunal,
                    canTransitionToGoneOnWake,
                    keyguardInteractor.primaryBouncerShowing,
                )
                .collect {
                    (
                        _,
                        biometricUnlockState,
                        occluded,
                        isIdleOnCommunal,
                        canTransitionToGoneOnWake,
                        primaryBouncerShowing) ->
                    startTransitionTo(
                        if (!deviceEntryRepository.isLockscreenEnabled()) {
                            KeyguardState.GONE
                        } else if (isWakeAndUnlock(biometricUnlockState.mode)) {
                            KeyguardState.GONE
                        } else if (canTransitionToGoneOnWake) {
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

    /** Figure out what state to transition to when we awake from DOZING. */
    private fun listenForWakeFromDozing() {
        if (!KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            powerInteractor.detailedWakefulness
                .filterRelevantKeyguardStateAnd { it.isAwake() }
                .sample(
                    communalInteractor.isIdleOnCommunal,
                    keyguardInteractor.biometricUnlockState,
                    canTransitionToGoneOnWake,
                    keyguardInteractor.primaryBouncerShowing,
                )
                .collect {
                    (
                        _,
                        isIdleOnCommunal,
                        biometricUnlockState,
                        canDismissLockscreen,
                        primaryBouncerShowing) ->
                    if (
                        !maybeStartTransitionToOccludedOrInsecureCamera() &&
                            // Handled by dismissFromDozing().
                            !isWakeAndUnlock(biometricUnlockState.mode)
                    ) {
                        startTransitionTo(
                            if (!KeyguardWmStateRefactor.isEnabled && canDismissLockscreen) {
                                KeyguardState.GONE
                            } else if (
                                KeyguardWmStateRefactor.isEnabled &&
                                    !deviceEntryRepository.isLockscreenEnabled()
                            ) {
                                KeyguardState.GONE
                            } else if (primaryBouncerShowing) {
                                KeyguardState.PRIMARY_BOUNCER
                            } else if (isIdleOnCommunal) {
                                KeyguardState.GLANCEABLE_HUB
                            } else {
                                KeyguardState.LOCKSCREEN
                            },
                            ownerReason = "waking from dozing"
                        )
                    }
                }
        }
    }

    /** Dismisses keyguard from the DOZING state. */
    fun dismissFromDozing() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
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
