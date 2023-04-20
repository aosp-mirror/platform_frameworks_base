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

import com.android.systemui.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromOccludedTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AnimationParams
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Breaks down OCCLUDED->LOCKSCREEN transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class OccludedToLockscreenTransitionViewModel
@Inject
constructor(
    private val interactor: KeyguardTransitionInteractor,
) {
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
            interactor.occludedToLockscreenTransition,
            params,
            totalDuration = TO_LOCKSCREEN_DURATION
        )
    }

    companion object {
        @JvmField val LOCKSCREEN_ANIMATION_DURATION_MS = TO_LOCKSCREEN_DURATION.inWholeMilliseconds
        val LOCKSCREEN_TRANSLATION_Y = AnimationParams(duration = TO_LOCKSCREEN_DURATION)
        val LOCKSCREEN_ALPHA =
            AnimationParams(startTime = 233.milliseconds, duration = 250.milliseconds)
    }
}
