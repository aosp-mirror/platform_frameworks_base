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
import com.android.systemui.Flags.communalHub
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@SysUISingleton
class FromDreamingTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.DREAMING,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
    ) {

    override fun start() {
        listenForDreamingToOccluded()
        listenForDreamingToGoneWhenDismissable()
        listenForDreamingToGoneFromBiometricUnlock()
        listenForDreamingToLockscreen()
        listenForDreamingToAodOrDozing()
        listenForTransitionToCamera(scope, keyguardInteractor)
        listenForDreamingToGlanceableHub()
    }

    private fun listenForDreamingToGlanceableHub() {
        if (!communalHub()) return
        scope.launch("$TAG#listenForDreamingToGlanceableHub", mainDispatcher) {
            glanceableHubTransitions.listenForGlanceableHubTransition(
                transitionOwnerName = TAG,
                fromState = KeyguardState.DREAMING,
                toState = KeyguardState.GLANCEABLE_HUB,
            )
        }
    }

    fun startToLockscreenTransition() {
        scope.launch {
            KeyguardWmStateRefactor.isUnexpectedlyInLegacyMode()
            if (
                transitionInteractor.startedKeyguardState.replayCache.last() ==
                    KeyguardState.DREAMING
            ) {
                startTransitionTo(KeyguardState.LOCKSCREEN)
            }
        }
    }

    fun startToGlanceableHubTransition() {
        scope.launch {
            KeyguardWmStateRefactor.isUnexpectedlyInLegacyMode()
            if (
                transitionInteractor.startedKeyguardState.replayCache.last() ==
                    KeyguardState.DREAMING
            ) {
                startTransitionTo(KeyguardState.GLANCEABLE_HUB)
            }
        }
    }

    private fun listenForDreamingToOccluded() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                combine(
                        keyguardInteractor.isDreaming,
                        keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop,
                        ::Pair
                    )
                    .sample(startedKeyguardTransitionStep, ::toTriple)
                    .filter { (isDreaming, _, startedStep) ->
                        !isDreaming && startedStep.to == KeyguardState.DREAMING
                    }
                    .collect { maybeStartTransitionToOccludedOrInsecureCamera() }
            }
        } else {
            scope.launch {
                combine(
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardInteractor.isDreaming,
                        ::Pair
                    )
                    .sample(startedKeyguardTransitionStep, Utils.Companion::toTriple)
                    .collect { (isOccluded, isDreaming, lastStartedTransition) ->
                        if (
                            isOccluded &&
                                !isDreaming &&
                                lastStartedTransition.to == KeyguardState.DREAMING
                        ) {
                            startTransitionTo(KeyguardState.OCCLUDED)
                        }
                    }
            }
        }
    }

    private fun listenForDreamingToLockscreen() {
        if (!KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop
                .filter { onTop -> !onTop }
                .sample(startedKeyguardState)
                .collect { startedState ->
                    if (startedState == KeyguardState.DREAMING) {
                        startTransitionTo(KeyguardState.LOCKSCREEN)
                    }
                }
        }
    }

    private fun listenForDreamingToGoneWhenDismissable() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sampleCombine(
                    keyguardInteractor.isKeyguardShowing,
                    keyguardInteractor.isKeyguardDismissible,
                    startedKeyguardTransitionStep,
                )
                .collect {
                    (isDreaming, isKeyguardShowing, isKeyguardDismissible, lastStartedTransition) ->
                    if (
                        !isDreaming &&
                            lastStartedTransition.to == KeyguardState.DREAMING &&
                            isKeyguardDismissible &&
                            !isKeyguardShowing
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForDreamingToGoneFromBiometricUnlock() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (biometricUnlockState, lastStartedTransitionStep) ->
                    if (
                        lastStartedTransitionStep.to == KeyguardState.DREAMING &&
                            biometricUnlockState == BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForDreamingToAodOrDozing() {
        scope.launch {
            combine(keyguardInteractor.dozeTransitionModel, finishedKeyguardState, ::Pair)
                .collect { (dozeTransitionModel, keyguardState) ->
                    if (keyguardState == KeyguardState.DREAMING) {
                        if (dozeTransitionModel.to == DozeStateModel.DOZE) {
                            startTransitionTo(KeyguardState.DOZING)
                        } else if (dozeTransitionModel.to == DozeStateModel.DOZE_AOD) {
                            startTransitionTo(KeyguardState.AOD)
                        }
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromDreamingTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_GLANCEABLE_HUB_DURATION = 1.seconds
        val TO_LOCKSCREEN_DURATION = 1167.milliseconds
    }
}
