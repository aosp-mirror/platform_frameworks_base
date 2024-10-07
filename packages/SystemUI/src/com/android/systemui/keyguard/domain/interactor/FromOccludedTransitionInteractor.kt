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
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.Flags.restartDreamOnUnocclude
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class FromOccludedTransitionInteractor
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
) :
    TransitionInteractor(
        fromState = KeyguardState.OCCLUDED,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        listenForOccludedToLockscreenOrHub()
        listenForOccludedToDreaming()
        listenForOccludedToAsleep()
        listenForOccludedToGone()
        listenForOccludedToAlternateBouncer()
        listenForOccludedToPrimaryBouncer()
    }

    private fun listenForOccludedToPrimaryBouncer() {
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .filterRelevantKeyguardStateAnd { isBouncerShowing -> isBouncerShowing }
                .collect { startTransitionTo(KeyguardState.PRIMARY_BOUNCER) }
        }
    }

    private fun listenForOccludedToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .filterRelevantKeyguardStateAnd { isAbleToDream -> isAbleToDream }
                .collect { startTransitionTo(KeyguardState.DREAMING) }
        }
    }

    private fun listenForOccludedToLockscreenOrHub() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop
                    .filterRelevantKeyguardStateAnd { onTop -> !onTop }
                    .sample(
                        communalInteractor.isIdleOnCommunal,
                        communalInteractor.showCommunalFromOccluded,
                        communalInteractor.dreamFromOccluded,
                    )
                    .collect { (_, isIdleOnCommunal, showCommunalFromOccluded, dreamFromOccluded) ->
                        startTransitionToLockscreenOrHub(
                            isIdleOnCommunal,
                            showCommunalFromOccluded,
                            dreamFromOccluded,
                        )
                    }
            }
        } else {
            scope.launch {
                keyguardInteractor.isKeyguardOccluded
                    .sample(
                        keyguardInteractor.isKeyguardShowing,
                        communalInteractor.isIdleOnCommunal,
                        communalInteractor.showCommunalFromOccluded,
                        communalInteractor.dreamFromOccluded,
                    )
                    .filterRelevantKeyguardStateAnd { (isOccluded, isShowing, _, _, _) ->
                        !isOccluded && isShowing
                    }
                    .collect { (_, _, isIdleOnCommunal, showCommunalFromOccluded, dreamFromOccluded)
                        ->
                        startTransitionToLockscreenOrHub(
                            isIdleOnCommunal,
                            showCommunalFromOccluded,
                            dreamFromOccluded,
                        )
                    }
            }
        }
    }

    private suspend fun startTransitionToLockscreenOrHub(
        isIdleOnCommunal: Boolean,
        showCommunalFromOccluded: Boolean,
        dreamFromOccluded: Boolean,
    ) {
        if (restartDreamOnUnocclude() && dreamFromOccluded) {
            startTransitionTo(KeyguardState.DREAMING)
        } else if (isIdleOnCommunal || showCommunalFromOccluded) {
            if (SceneContainerFlag.isEnabled) return
            if (communalSceneKtfRefactor()) {
                communalSceneInteractor.changeScene(
                    newScene = CommunalScenes.Communal,
                    loggingReason = "occluded to hub",
                    transitionKey = CommunalTransitionKeys.SimpleFade,
                )
            } else {
                startTransitionTo(KeyguardState.GLANCEABLE_HUB)
            }
        } else {
            startTransitionTo(KeyguardState.LOCKSCREEN)
        }
    }

    /** Starts a transition to dismiss the keyguard from the OCCLUDED state. */
    fun dismissFromOccluded() {
        scope.launch {
            startTransitionTo(KeyguardState.GONE, ownerReason = "Dismiss from occluded")
        }
    }

    private fun listenForOccludedToGone() {
        if (KeyguardWmStateRefactor.isEnabled) {
            // We don't think OCCLUDED to GONE is possible. You should always have to go via a
            // *_BOUNCER state to end up GONE. Launching an activity over a dismissable keyguard
            // dismisses it, and even "extend unlock" doesn't unlock the device in the background.
            // If we're wrong - sorry, add it back here.
            return
        }
        // TODO(b/353545202): Adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(keyguardInteractor.isKeyguardShowing, ::Pair)
                .filterRelevantKeyguardStateAnd { (occluded, showing) -> !occluded && !showing }
                .collect {
                    // Occlusion signals come from the framework, and should interrupt any
                    // existing transition
                    startTransitionTo(KeyguardState.GONE)
                }
        }
    }

    private fun listenForOccludedToAsleep() {
        scope.launch { listenForSleepTransition() }
    }

    private fun listenForOccludedToAlternateBouncer() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                .filterRelevantKeyguardStateAnd { isAlternateBouncerShowing ->
                    isAlternateBouncerShowing
                }
                .collect { startTransitionTo(KeyguardState.ALTERNATE_BOUNCER) }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator =
                when (toState) {
                    KeyguardState.ALTERNATE_BOUNCER -> Interpolators.FAST_OUT_SLOW_IN
                    else -> Interpolators.LINEAR
                }

            duration =
                when (toState) {
                    KeyguardState.ALTERNATE_BOUNCER -> TO_ALTERNATE_BOUNCER_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromOccludedTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_ALTERNATE_BOUNCER_DURATION = DEFAULT_DURATION
        val TO_AOD_DURATION = DEFAULT_DURATION
        val TO_DOZING_DURATION = DEFAULT_DURATION
        val TO_GLANCEABLE_HUB_DURATION = 250.milliseconds
        val TO_LOCKSCREEN_DURATION = 933.milliseconds
    }
}
