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
import com.android.systemui.keyguard.domain.interactor.FromGoneTransitionInteractor.Companion.TO_DREAMING_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AnimationParams
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Breaks down GONE->DREAMING transition into discrete steps for corresponding views to consume. */
@SysUISingleton
class GoneToDreamingTransitionViewModel
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
            interactor.goneToDreamingTransition
                .filter { it.transitionState == FINISHED || it.transitionState == CANCELED }
                .map { 0f }
        )
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> = flowForAnimation(LOCKSCREEN_ALPHA).map { 1f - it }

    private fun flowForAnimation(params: AnimationParams): Flow<Float> {
        return interactor.transitionStepAnimation(
            interactor.goneToDreamingTransition,
            params,
            totalDuration = TO_DREAMING_DURATION
        )
    }

    companion object {
        val LOCKSCREEN_TRANSLATION_Y = AnimationParams(duration = 500.milliseconds)
        val LOCKSCREEN_ALPHA = AnimationParams(duration = 250.milliseconds)
    }
}
