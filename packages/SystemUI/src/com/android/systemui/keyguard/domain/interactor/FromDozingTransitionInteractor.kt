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
import android.annotation.SuppressLint
import android.app.DreamManager
import com.android.app.animation.Interpolators
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@SysUISingleton
class FromDozingTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    keyguardInteractor: KeyguardInteractor,
    powerInteractor: PowerInteractor,
    private val communalInteractor: CommunalInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    val deviceEntryInteractor: DeviceEntryInteractor,
    private val wakeToGoneInteractor: KeyguardWakeDirectlyToGoneInteractor,
    private val dreamManager: DreamManager,
) :
    TransitionInteractor(
        fromState = KeyguardState.DOZING,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        listenForDozingToAny()
        listenForDozingToGoneViaBiometrics()
        listenForWakeFromDozing()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private fun canDismissLockscreen(): Boolean {
        return !keyguardInteractor.isKeyguardShowing.value &&
            keyguardInteractor.isKeyguardDismissible.value
    }

    private fun listenForDozingToGoneViaBiometrics() {
        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        // This is separate from `listenForDozingToAny` because any delay on wake and unlock will
        // cause a noticeable issue with animations
        scope.launch {
            powerInteractor.isAwake
                .filterRelevantKeyguardStateAnd { isAwake -> isAwake }
                .sample(keyguardInteractor.biometricUnlockState, ::Pair)
                .collect {
                    (
                        _,
                        biometricUnlockState,
                    ) ->
                    if (isWakeAndUnlock(biometricUnlockState.mode)) {
                        if (SceneContainerFlag.isEnabled) {
                            // TODO(b/360368320): Adapt for scene framework
                        } else {
                            startTransitionTo(
                                KeyguardState.GONE,
                                ownerReason = "biometric wake and unlock",
                            )
                        }
                    }
                }
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenForDozingToAny() {
        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            powerInteractor.isAwake
                .debounce(50L)
                .filterRelevantKeyguardStateAnd { isAwake -> isAwake }
                .sample(
                    communalInteractor.isCommunalAvailable,
                    communalSceneInteractor.isIdleOnCommunal,
                )
                .collect { (_, isCommunalAvailable, isIdleOnCommunal) ->
                    val isKeyguardOccludedLegacy = keyguardInteractor.isKeyguardOccluded.value
                    val primaryBouncerShowing = keyguardInteractor.primaryBouncerShowing.value

                    if (!deviceEntryInteractor.isLockscreenEnabled()) {
                        if (!SceneContainerFlag.isEnabled) {
                            startTransitionTo(KeyguardState.GONE)
                        }
                    } else if (canDismissLockscreen()) {
                        if (!SceneContainerFlag.isEnabled) {
                            startTransitionTo(KeyguardState.GONE)
                        }
                    } else if (primaryBouncerShowing) {
                        if (!SceneContainerFlag.isEnabled) {
                            startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                        }
                    } else if (isKeyguardOccludedLegacy) {
                        startTransitionTo(KeyguardState.OCCLUDED)
                    } else if (isIdleOnCommunal && !communalSceneKtfRefactor()) {
                        if (!SceneContainerFlag.isEnabled) {
                            startTransitionTo(KeyguardState.GLANCEABLE_HUB)
                        }
                    } else if (isCommunalAvailable && dreamManager.canStartDreaming(false)) {
                        // Using false for isScreenOn as canStartDreaming returns false if any
                        // dream, including doze, is active.
                        // This case handles tapping the power button to transition through
                        // dream -> off -> hub.
                        if (!SceneContainerFlag.isEnabled) {
                            transitionToGlanceableHub()
                        }
                    } else {
                        startTransitionTo(KeyguardState.LOCKSCREEN)
                    }
                }
        }
    }

    /** Figure out what state to transition to when we awake from DOZING. */
    @SuppressLint("MissingPermission")
    private fun listenForWakeFromDozing() {
        if (!KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            powerInteractor.detailedWakefulness
                .filterRelevantKeyguardStateAnd { it.isAwake() }
                .sample(
                    communalInteractor.isCommunalAvailable,
                    communalSceneInteractor.isIdleOnCommunal,
                    keyguardInteractor.biometricUnlockState,
                    wakeToGoneInteractor.canWakeDirectlyToGone,
                    keyguardInteractor.primaryBouncerShowing,
                )
                .collect {
                    (
                        _,
                        isCommunalAvailable,
                        isIdleOnCommunal,
                        biometricUnlockState,
                        canWakeDirectlyToGone,
                        primaryBouncerShowing) ->
                    if (
                        !maybeStartTransitionToOccludedOrInsecureCamera { state, reason ->
                            startTransitionTo(state, ownerReason = reason)
                        } &&
                            // Handled by dismissFromDozing().
                            !isWakeAndUnlock(biometricUnlockState.mode)
                    ) {
                        if (canWakeDirectlyToGone) {
                            if (!SceneContainerFlag.isEnabled) {
                                startTransitionTo(
                                    KeyguardState.GONE,
                                    ownerReason = "waking from dozing",
                                )
                            }
                        } else if (primaryBouncerShowing) {
                            if (!SceneContainerFlag.isEnabled) {
                                startTransitionTo(
                                    KeyguardState.PRIMARY_BOUNCER,
                                    ownerReason = "waking from dozing",
                                )
                            }
                        } else if (isIdleOnCommunal && !communalSceneKtfRefactor()) {
                            if (!SceneContainerFlag.isEnabled) {
                                startTransitionTo(
                                    KeyguardState.GLANCEABLE_HUB,
                                    ownerReason = "waking from dozing",
                                )
                            }
                        } else if (isCommunalAvailable && dreamManager.canStartDreaming(true)) {
                            if (!SceneContainerFlag.isEnabled) {
                                transitionToGlanceableHub()
                            }
                        } else {
                            startTransitionTo(
                                KeyguardState.LOCKSCREEN,
                                ownerReason = "waking from dozing",
                            )
                        }
                    }
                }
        }
    }

    private suspend fun transitionToGlanceableHub() {
        if (communalSceneKtfRefactor()) {
            communalSceneInteractor.snapToScene(
                newScene = CommunalScenes.Communal,
                loggingReason = "from dozing to hub",
            )
        } else {
            startTransitionTo(KeyguardState.GLANCEABLE_HUB)
        }
    }

    /** Dismisses keyguard from the DOZING state. */
    fun dismissFromDozing() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.GONE -> TO_GONE_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    KeyguardState.OCCLUDED -> TO_OCCLUDED_DURATION
                    KeyguardState.PRIMARY_BOUNCER -> TO_PRIMARY_BOUNCER_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromDozingTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_GLANCEABLE_HUB_DURATION = DEFAULT_DURATION
        val TO_GONE_DURATION = DEFAULT_DURATION
        val TO_LOCKSCREEN_DURATION = DEFAULT_DURATION
        val TO_OCCLUDED_DURATION = 550.milliseconds
        val TO_PRIMARY_BOUNCER_DURATION = DEFAULT_DURATION
    }
}
