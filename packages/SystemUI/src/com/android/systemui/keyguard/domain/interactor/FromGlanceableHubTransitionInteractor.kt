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
import com.android.systemui.Flags
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class FromGlanceableHubTransitionInteractor
@Inject
constructor(
    @Background private val scope: CoroutineScope,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    private val powerInteractor: PowerInteractor,
    @Main mainDispatcher: CoroutineDispatcher,
    @Background bgDispatcher: CoroutineDispatcher,
) :
    TransitionInteractor(
        fromState = KeyguardState.GLANCEABLE_HUB,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {
    override fun start() {
        if (!Flags.communalHub()) {
            return
        }
        listenForHubToLockscreen()
        listenForHubToDozing()
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration = DEFAULT_DURATION.inWholeMilliseconds
        }
    }

    /**
     * Listens for the glanceable hub transition to lock screen and directly drives the keyguard
     * transition.
     */
    private fun listenForHubToLockscreen() {
        glanceableHubTransitions.listenForLockscreenAndHubTransition(
            transitionName = "listenForHubToLockscreen",
            transitionOwnerName = TAG,
            toScene = CommunalSceneKey.Blank
        )
    }

    private fun listenForHubToDozing() {
        scope.launch {
            powerInteractor.isAsleep.sample(startedKeyguardTransitionStep, ::Pair).collect {
                (isAsleep, lastStartedStep) ->
                if (lastStartedStep.to == fromState && isAsleep) {
                    startTransitionTo(
                        toState = KeyguardState.DOZING,
                        modeOnCanceled = TransitionModeOnCanceled.LAST_VALUE,
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "FromGlanceableHubTransitionInteractor"
        val DEFAULT_DURATION = 400.milliseconds
        val TO_LOCKSCREEN_DURATION = DEFAULT_DURATION
    }
}
