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
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AnimationParams
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Breaks down DREAMING->LOCKSCREEN transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class DreamingToLockscreenTransitionViewModel
@Inject
constructor(
    private val interactor: KeyguardTransitionInteractor,
) {

    /** Dream overlay y-translation on exit */
    fun dreamOverlayTranslationY(translatePx: Int): Flow<Float> {
        return flowForAnimation(DREAM_OVERLAY_TRANSLATION_Y).map { value ->
            EMPHASIZED_ACCELERATE.getInterpolation(value) * translatePx
        }
    }
    /** Dream overlay views alpha - fade out */
    val dreamOverlayAlpha: Flow<Float> = flowForAnimation(DREAM_OVERLAY_ALPHA).map { 1f - it }

    /** Lockscreen views y-translation */
    fun lockscreenTranslationY(translatePx: Int): Flow<Float> {
        return flowForAnimation(LOCKSCREEN_TRANSLATION_Y).map { value ->
            -translatePx + (EMPHASIZED_DECELERATE.getInterpolation(value) * translatePx)
        }
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> = flowForAnimation(LOCKSCREEN_ALPHA)

    private fun flowForAnimation(params: AnimationParams): Flow<Float> {
        return interactor.transitionStepAnimation(
            interactor.dreamingToLockscreenTransition,
            params,
            totalDuration = TO_LOCKSCREEN_DURATION
        )
    }

    companion object {
        /* Length of time before ending the dream activity, in order to start unoccluding */
        val DREAM_ANIMATION_DURATION = 250.milliseconds
        @JvmField
        val LOCKSCREEN_ANIMATION_DURATION_MS =
            (TO_LOCKSCREEN_DURATION - DREAM_ANIMATION_DURATION).inWholeMilliseconds

        val DREAM_OVERLAY_TRANSLATION_Y = AnimationParams(duration = 600.milliseconds)
        val DREAM_OVERLAY_ALPHA = AnimationParams(duration = 250.milliseconds)
        val LOCKSCREEN_TRANSLATION_Y = AnimationParams(duration = TO_LOCKSCREEN_DURATION)
        val LOCKSCREEN_ALPHA =
            AnimationParams(startTime = 233.milliseconds, duration = 250.milliseconds)
    }
}
