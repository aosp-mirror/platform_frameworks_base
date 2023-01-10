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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor.Companion.TO_OCCLUDED_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AnimationParams
import com.android.systemui.keyguard.shared.model.TransitionState
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Breaks down LOCKSCREEN->OCCLUDED transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class LockscreenToOccludedTransitionViewModel
@Inject
constructor(
    private val interactor: KeyguardTransitionInteractor,
) {

    /** Lockscreen views y-translation */
    fun lockscreenTranslationY(translatePx: Int): Flow<Float> {
        return merge(
            flowForAnimation(LOCKSCREEN_TRANSLATION_Y).map { value ->
                (EMPHASIZED_ACCELERATE.getInterpolation(value) * translatePx)
            },
            // On end, reset the translation to 0
            interactor.lockscreenToOccludedTransition
                .filter { step -> step.transitionState == TransitionState.FINISHED }
                .map { 0f }
        )
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> = flowForAnimation(LOCKSCREEN_ALPHA).map { 1f - it }

    private fun flowForAnimation(params: AnimationParams): Flow<Float> {
        return interactor.transitionStepAnimation(
            interactor.lockscreenToOccludedTransition,
            params,
            totalDuration = TO_OCCLUDED_DURATION
        )
    }

    companion object {
        val LOCKSCREEN_TRANSLATION_Y = AnimationParams(duration = TO_OCCLUDED_DURATION)
        val LOCKSCREEN_ALPHA = AnimationParams(duration = 250.milliseconds)
    }
}
