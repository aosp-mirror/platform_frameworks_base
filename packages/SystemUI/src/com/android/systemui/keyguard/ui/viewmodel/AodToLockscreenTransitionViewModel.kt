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

import android.util.MathUtils
import com.android.app.animation.Interpolators.FAST_OUT_SLOW_IN
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Breaks down AOD->LOCKSCREEN transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class AodToLockscreenTransitionViewModel
@Inject
constructor(
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    shadeInteractor: ShadeInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_LOCKSCREEN_DURATION,
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
        )

    /**
     * Begin the transition from wherever the y-translation value is currently. This helps ensure a
     * smooth transition if a transition in canceled.
     */
    fun translationY(currentTranslationY: () -> Float?): Flow<StateToValue> {
        var startValue = 0f
        return transitionAnimation.sharedFlowWithState(
            duration = 500.milliseconds,
            onStart = { startValue = currentTranslationY() ?: 0f },
            onStep = { MathUtils.lerp(startValue, 0f, FAST_OUT_SLOW_IN.getInterpolation(it)) },
        )
    }

    /** Ensure alpha is set to be visible */
    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        return transitionAnimation.sharedFlow(
            duration = 500.milliseconds,
            onStart = { startAlpha = viewState.alpha() },
            onStep = { MathUtils.lerp(startAlpha, 1f, it) },
        )
    }

    val notificationAlpha: Flow<Float> =
        combine(
            shadeInteractor.shadeExpansion.map { it > 0f },
            shadeInteractor.qsExpansion.map { it > 0f },
            transitionAnimation.sharedFlow(
                duration = 500.milliseconds,
                onStep = { it },
                onCancel = { 1f },
            ),
        ) { isShadeExpanded, isQsExpanded, alpha ->
            if (isShadeExpanded || isQsExpanded) {
                // One example of this happening is dragging a notification while pulsing on AOD
                1f
            } else {
                alpha
            }
        }

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 167.milliseconds,
            startTime = 67.milliseconds,
            onStep = { it },
            onCancel = { 0f },
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { it },
            onFinish = { 1f },
        )

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 500.milliseconds,
            onStep = { 1f },
        )
}
