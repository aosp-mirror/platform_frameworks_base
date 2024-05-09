/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launch
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class FromGlanceableHubTransitionInteractor
@Inject
constructor(
    @Background private val scope: CoroutineScope,
    @Main mainDispatcher: CoroutineDispatcher,
    @Background bgDispatcher: CoroutineDispatcher,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    private val keyguardInteractor: KeyguardInteractor,
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.GLANCEABLE_HUB,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
    ) {

    override fun start() {
        if (!Flags.communalHub()) {
            return
        }
        listenForHubToLockscreenOrDreaming()
        listenForHubToDozing()
        listenForHubToPrimaryBouncer()
        listenForHubToAlternateBouncer()
        listenForHubToOccluded()
        listenForHubToGone()
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

    /**
     * Listens for the glanceable hub transition to lock screen and directly drives the keyguard
     * transition.
     */
    private fun listenForHubToLockscreenOrDreaming() {
        scope.launch("$TAG#listenForGlanceableHubToLockscreenOrDream") {
            keyguardInteractor.isDreaming.collectLatest { dreaming ->
                withContext(mainDispatcher) {
                    val toState =
                        if (dreaming) {
                            KeyguardState.DREAMING
                        } else {
                            KeyguardState.LOCKSCREEN
                        }
                    glanceableHubTransitions.listenForGlanceableHubTransition(
                        transitionOwnerName = TAG,
                        fromState = KeyguardState.GLANCEABLE_HUB,
                        toState = toState,
                    )
                }
            }
        }
    }

    private fun listenForHubToPrimaryBouncer() {
        scope.launch("$TAG#listenForHubToPrimaryBouncer") {
            keyguardInteractor.primaryBouncerShowing
                .filterRelevantKeyguardStateAnd { primaryBouncerShowing -> primaryBouncerShowing }
                .collect { startTransitionTo(KeyguardState.PRIMARY_BOUNCER) }
        }
    }

    private fun listenForHubToAlternateBouncer() {
        scope.launch("$TAG#listenForHubToAlternateBouncer") {
            keyguardInteractor.alternateBouncerShowing
                .filterRelevantKeyguardStateAnd { alternateBouncerShowing ->
                    alternateBouncerShowing
                }
                .collect { pair -> startTransitionTo(KeyguardState.ALTERNATE_BOUNCER) }
        }
    }

    private fun listenForHubToDozing() {
        scope.launch {
            powerInteractor.isAsleep
                .filterRelevantKeyguardStateAnd { isAsleep -> isAsleep }
                .collect {
                    startTransitionTo(
                        toState = KeyguardState.DOZING,
                        modeOnCanceled = TransitionModeOnCanceled.LAST_VALUE,
                    )
                }
        }
    }

    private fun listenForHubToOccluded() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop
                    .filterRelevantKeyguardStateAnd { onTop -> onTop }
                    .collect { maybeStartTransitionToOccludedOrInsecureCamera() }
            }
        } else {
            scope.launch {
                allOf(keyguardInteractor.isKeyguardOccluded, not(keyguardInteractor.isDreaming))
                    .filterRelevantKeyguardStateAnd { isOccludedAndNotDreaming ->
                        isOccludedAndNotDreaming
                    }
                    .collect { isOccludedAndNotDreaming ->
                        startTransitionTo(KeyguardState.OCCLUDED)
                    }
            }
        }
    }

    private fun listenForHubToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .filterRelevantKeyguardStateAnd { isKeyguardGoingAway -> isKeyguardGoingAway }
                .collect { startTransitionTo(KeyguardState.GONE) }
        }
    }

    companion object {
        const val TAG = "FromGlanceableHubTransitionInteractor"
        val DEFAULT_DURATION = 1.seconds
        val TO_LOCKSCREEN_DURATION = DEFAULT_DURATION
    }
}
