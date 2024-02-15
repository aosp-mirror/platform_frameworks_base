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

import com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.FromGoneTransitionInteractor.Companion.TO_AOD_DURATION
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Breaks down GONE->AOD transition into discrete steps for corresponding views to consume. */
@ExperimentalCoroutinesApi
@SysUISingleton
class GoneToAodTransitionViewModel
@Inject
constructor(
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_AOD_DURATION,
            from = KeyguardState.GONE,
            to = KeyguardState.AOD,
        )

    /** y-translation from the top of the screen for AOD */
    fun enterFromTopTranslationY(translatePx: Int): Flow<StateToValue> {
        return transitionAnimation.sharedFlowWithState(
            startTime = 600.milliseconds,
            duration = 500.milliseconds,
            onStart = { translatePx },
            onStep = { translatePx + it * -translatePx },
            onFinish = { 0f },
            onCancel = { 0f },
            interpolator = EMPHASIZED_DECELERATE,
        )
    }

    /** alpha animation upon entering AOD */
    val enterFromTopAnimationAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            startTime = 700.milliseconds,
            duration = 400.milliseconds,
            onStart = { 0f },
            onStep = { it },
            onFinish = { 1f },
            onCancel = { 1f },
        )
    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)
    override val deviceEntryParentViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest { udfpsEnrolled ->
            if (udfpsEnrolled) {
                // fade in at the end of the transition to give time for FP to start running
                // and avoid a flicker of the unlocked icon
                transitionAnimation.sharedFlow(
                    startTime = 1100.milliseconds,
                    duration = 200.milliseconds,
                    onStep = { it },
                    onFinish = { 1f },
                )
            } else {
                emptyFlow()
            }
        }
}
