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
import com.android.app.tracing.coroutines.launch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.Utils.Companion.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce

@SysUISingleton
class FromAodTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    keyguardInteractor: KeyguardInteractor,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    val deviceEntryRepository: DeviceEntryRepository,
) :
    TransitionInteractor(
        fromState = KeyguardState.AOD,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        listenForAodToAwake()
        listenForAodToOccluded()
        listenForAodToPrimaryBouncer()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private val canDismissLockscreen: Flow<Boolean> =
        combine(
            keyguardInteractor.isKeyguardShowing,
            keyguardInteractor.isKeyguardDismissible,
            keyguardInteractor.biometricUnlockState,
        ) { isKeyguardShowing, isKeyguardDismissible, biometricUnlockState ->
            (isWakeAndUnlock(biometricUnlockState.mode) ||
                (!isKeyguardShowing && isKeyguardDismissible))
        }

    /**
     * Listen for the signal that we're waking up and figure what state we need to transition to.
     */
    private fun listenForAodToAwake() {
        // Use PowerInteractor's wakefulness, which is the earliest wake signal available. We
        // have all of the information we need at this time to make a decision about where to
        // transition.
        scope.launch("$TAG#listenForAodToAwake") {
            powerInteractor.detailedWakefulness
                .filterRelevantKeyguardStateAnd { wakefulness -> wakefulness.isAwake() }
                .debounce(50L)
                .sample(
                    startedKeyguardTransitionStep,
                    keyguardInteractor.biometricUnlockState,
                    keyguardInteractor.primaryBouncerShowing,
                    keyguardInteractor.isKeyguardOccluded,
                    canDismissLockscreen,
                )
                .collect {
                    (
                        _,
                        startedStep,
                        biometricUnlockState,
                        primaryBouncerShowing,
                        isKeyguardOccludedLegacy,
                        canDismissLockscreen,
                    ) ->
                    if (!maybeHandleInsecurePowerGesture()) {
                        val shouldTransitionToLockscreen =
                            if (KeyguardWmStateRefactor.isEnabled) {
                                // Check with the superclass to see if an occlusion transition is
                                // needed. Also, don't react to wake and unlock events, as we'll be
                                // receiving a call to #dismissAod() shortly when the authentication
                                // completes.
                                !maybeStartTransitionToOccludedOrInsecureCamera() &&
                                    !isWakeAndUnlock(biometricUnlockState.mode) &&
                                    !primaryBouncerShowing
                            } else {
                                !isKeyguardOccludedLegacy &&
                                    !isWakeAndUnlock(biometricUnlockState.mode) &&
                                    !primaryBouncerShowing
                            }

                        // With the refactor enabled, maybeStartTransitionToOccludedOrInsecureCamera
                        // handles transitioning to OCCLUDED.
                        val shouldTransitionToOccluded =
                            !KeyguardWmStateRefactor.isEnabled && isKeyguardOccludedLegacy

                        val shouldTransitionToGone =
                            (!KeyguardWmStateRefactor.isEnabled && canDismissLockscreen) ||
                                (KeyguardWmStateRefactor.isEnabled &&
                                    !deviceEntryRepository.isLockscreenEnabled())

                        if (shouldTransitionToGone) {
                            startTransitionTo(
                                toState = KeyguardState.GONE,
                            )
                        } else if (shouldTransitionToLockscreen) {
                            val modeOnCanceled =
                                if (startedStep.from == KeyguardState.LOCKSCREEN) {
                                    TransitionModeOnCanceled.REVERSE
                                } else if (startedStep.from == KeyguardState.GONE) {
                                    TransitionModeOnCanceled.RESET
                                } else {
                                    TransitionModeOnCanceled.LAST_VALUE
                                }

                            startTransitionTo(
                                toState = KeyguardState.LOCKSCREEN,
                                modeOnCanceled = modeOnCanceled,
                                ownerReason = "listen for aod to awake"
                            )
                        } else if (shouldTransitionToOccluded) {
                            startTransitionTo(
                                toState = KeyguardState.OCCLUDED,
                                ownerReason = "waking up and isOccluded=true",
                            )
                        }
                    }
                }
        }
    }

    /**
     * There are cases where the transition to AOD begins but never completes, such as tapping power
     * during an incoming phone call when unlocked. In this case, GONE->AOD should be interrupted to
     * run AOD->OCCLUDED.
     */
    private fun listenForAodToOccluded() {
        if (KeyguardWmStateRefactor.isEnabled) {
            // Handled by calls to maybeStartTransitionToOccludedOrInsecureCamera on waking.
            return
        }

        scope.launch("$TAG#listenForAodToOccluded") {
            keyguardInteractor.isKeyguardOccluded
                .filterRelevantKeyguardStateAnd { isOccluded -> isOccluded }
                .collect {
                    if (!maybeHandleInsecurePowerGesture()) {
                        startTransitionTo(
                            toState = KeyguardState.OCCLUDED,
                            modeOnCanceled = TransitionModeOnCanceled.RESET,
                            ownerReason = "isOccluded = true",
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
        if (SceneContainerFlag.isEnabled) return
        scope.launch("$TAG#listenForAodToPrimaryBouncer") {
            keyguardInteractor.primaryBouncerShowing
                .filterRelevantKeyguardStateAnd { primaryBouncerShowing -> primaryBouncerShowing }
                .collect { startTransitionTo(KeyguardState.PRIMARY_BOUNCER) }
        }
    }

    /**
     * Dismisses AOD and transitions to GONE. This is called whenever authentication occurs while on
     * AOD.
     */
    fun dismissAod() {
        scope.launch("$TAG#dismissAod") { startTransitionTo(KeyguardState.GONE) }
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
        private const val TAG = "FromAodTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 500.milliseconds
        val TO_GONE_DURATION = DEFAULT_DURATION
        val TO_OCCLUDED_DURATION = DEFAULT_DURATION
        val TO_PRIMARY_BOUNCER_DURATION = DEFAULT_DURATION
    }
}
