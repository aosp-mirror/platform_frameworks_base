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
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down AOD->LOCKSCREEN transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class AodToLockscreenTransitionViewModel
@Inject
constructor(
    shadeInteractor: ShadeInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_LOCKSCREEN_DURATION,
            edge = Edge.create(from = AOD, to = LOCKSCREEN),
        )

    private var isShadeExpanded = false

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
        transitionAnimation.sharedFlow(
            duration = 500.milliseconds,
            onStart = {
                isShadeExpanded =
                    shadeInteractor.shadeExpansion.value > 0f ||
                        shadeInteractor.qsExpansion.value > 0f
            },
            onStep = {
                if (isShadeExpanded) {
                    1f
                } else {
                    it
                }
            },
        )

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
            onCancel = { 1f },
            onFinish = { 1f },
        )

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)
}
