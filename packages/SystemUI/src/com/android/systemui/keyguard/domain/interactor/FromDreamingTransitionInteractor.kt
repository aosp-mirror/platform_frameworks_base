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
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    keyguardInteractor: KeyguardInteractor,
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
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        listenForDreamingToAlternateBouncer()
        listenForDreamingToOccluded()
        listenForDreamingToGoneWhenDismissable()
        listenForDreamingToGoneFromBiometricUnlock()
        listenForDreamingToLockscreen()
        listenForDreamingToAodOrDozing()
        listenForTransitionToCamera(scope, keyguardInteractor)
        listenForDreamingToGlanceableHub()
        listenForDreamingToPrimaryBouncer()
    }

    private fun listenForDreamingToAlternateBouncer() {
        scope.launch("$TAG#listenForDreamingToAlternateBouncer") {
            keyguardInteractor.alternateBouncerShowing
                .filterRelevantKeyguardStateAnd { isAlternateBouncerShowing ->
                    isAlternateBouncerShowing
                }
                .collect { startTransitionTo(KeyguardState.ALTERNATE_BOUNCER) }
        }
    }

    private fun listenForDreamingToGlanceableHub() {
        if (!communalHub()) return
        if (SceneContainerFlag.isEnabled)
            return // TODO(b/336576536): Check if adaptation for scene framework is needed
        scope.launch("$TAG#listenForDreamingToGlanceableHub", mainDispatcher) {
            glanceableHubTransitions.listenForGlanceableHubTransition(
                transitionOwnerName = TAG,
                fromState = KeyguardState.DREAMING,
                toState = KeyguardState.GLANCEABLE_HUB,
            )
        }
    }

    private fun listenForDreamingToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isBouncerShowing, lastStartedTransitionStep) = pair
                    if (
                        isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.DREAMING
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    fun startToLockscreenTransition() {
        scope.launch {
            if (
                transitionInteractor.startedKeyguardState.replayCache.last() ==
                    KeyguardState.DREAMING
            ) {
                startTransitionTo(KeyguardState.LOCKSCREEN)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun listenForDreamingToOccluded() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                combine(
                        keyguardInteractor.isDreaming,
                        keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop,
                        ::Pair
                    )
                    .filterRelevantKeyguardStateAnd { (isDreaming, _) -> !isDreaming }
                    .collect { maybeStartTransitionToOccludedOrInsecureCamera() }
            }
        } else {
            scope.launch {
                combine(
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardInteractor.isDreaming
                            // Debounce the dreaming signal since there is a race condition between
                            // the occluded and dreaming signals. We therefore add a small delay
                            // to give enough time for occluded to flip to false when the dream
                            // ends, to avoid transitioning to OCCLUDED erroneously when exiting
                            // the dream.
                            .debounce(100.milliseconds),
                        ::Pair
                    )
                    .filterRelevantKeyguardStateAnd { (isOccluded, isDreaming) ->
                        isOccluded && !isDreaming
                    }
                    .collect {
                        startTransitionTo(
                            toState = KeyguardState.OCCLUDED,
                            ownerReason = "Occluded but no longer dreaming",
                        )
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
                .filterRelevantKeyguardStateAnd { onTop -> !onTop }
                .collect { startTransitionTo(KeyguardState.LOCKSCREEN) }
        }
    }

    private fun listenForDreamingToGoneWhenDismissable() {
        if (SceneContainerFlag.isEnabled)
            return // TODO(b/336576536): Check if adaptation for scene framework is needed
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sampleCombine(
                    keyguardInteractor.isKeyguardShowing,
                    keyguardInteractor.isKeyguardDismissible,
                )
                .filterRelevantKeyguardStateAnd {
                    (isDreaming, isKeyguardShowing, isKeyguardDismissible) ->
                    !isDreaming && isKeyguardDismissible && !isKeyguardShowing
                }
                .collect { startTransitionTo(KeyguardState.GONE) }
        }
    }

    private fun listenForDreamingToGoneFromBiometricUnlock() {
        // TODO(b/336576536): Check if adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .filterRelevantKeyguardStateAnd { biometricUnlockState ->
                    biometricUnlockState.mode == BiometricUnlockMode.WAKE_AND_UNLOCK_FROM_DREAM
                }
                .collect { startTransitionTo(KeyguardState.GONE) }
        }
    }

    private fun listenForDreamingToAodOrDozing() {
        scope.launch {
            keyguardInteractor.dozeTransitionModel.filterRelevantKeyguardState().collect {
                dozeTransitionModel ->
                if (dozeTransitionModel.to == DozeStateModel.DOZE) {
                    startTransitionTo(KeyguardState.DOZING)
                } else if (dozeTransitionModel.to == DozeStateModel.DOZE_AOD) {
                    startTransitionTo(KeyguardState.AOD)
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
        val TO_AOD_DURATION = 300.milliseconds
        val TO_GONE_DURATION = DEFAULT_DURATION
    }
}
