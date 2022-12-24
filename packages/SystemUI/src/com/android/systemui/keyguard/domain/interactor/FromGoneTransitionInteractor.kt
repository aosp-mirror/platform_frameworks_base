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
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class FromGoneTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromGoneTransitionInteractor::class.simpleName!!) {

    override fun start() {
        listenForGoneToAod()
        listenForGoneToDreaming()
    }

    private fun listenForGoneToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (isAbleToDream, keyguardState) = pair
                    if (isAbleToDream && keyguardState == KeyguardState.GONE) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.GONE,
                                KeyguardState.DREAMING,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForGoneToAod() {
        scope.launch {
            keyguardInteractor.wakefulnessModel
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (wakefulnessState, keyguardState) = pair
                    if (
                        keyguardState == KeyguardState.GONE &&
                            wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.GONE,
                                KeyguardState.AOD,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun getAnimator(): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(TRANSITION_DURATION_MS)
        }
    }

    companion object {
        private const val TRANSITION_DURATION_MS = 500L
    }
}
