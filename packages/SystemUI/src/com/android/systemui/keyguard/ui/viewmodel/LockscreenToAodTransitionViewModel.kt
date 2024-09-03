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
import com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason.FOLD
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transform

/**
 * Breaks down LOCKSCREEN->AOD transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToAodTransitionViewModel
@Inject
constructor(
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    private val powerInteractor: PowerInteractor,
    shadeDependentFlows: ShadeDependentFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = FromLockscreenTransitionInteractor.TO_AOD_DURATION,
            edge = Edge.create(from = LOCKSCREEN, to = AOD),
        )

    private val transitionAnimationOnFold =
        animationFlow.setup(
            duration = FromLockscreenTransitionInteractor.TO_AOD_FOLD_DURATION,
            edge = Edge.create(from = LOCKSCREEN, to = AOD),
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
            flowWhenShadeIsNotExpanded =
                transitionAnimation.sharedFlow(
                    duration = 300.milliseconds,
                    onStep = { 1 - it },
                    onCancel = { 0f },
                    onFinish = { 0f },
                ),
        )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1 - it },
            onFinish = { 0f },
            onCancel = { 1f },
        )

    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        return transitionAnimation
            .sharedFlow(
                duration = 500.milliseconds,
                onStart = { startAlpha = viewState.alpha() },
                onStep = { MathUtils.lerp(startAlpha, 1f, it) },
            )
            .sample(powerInteractor.detailedWakefulness, ::Pair)
            .transform { (alpha, wakefulness) ->
                if (wakefulness.lastSleepReason != FOLD) {
                    emit(alpha)
                }
            }
    }

    val lockscreenAlphaOnFold: Flow<Float> =
        transitionAnimationOnFold
            .sharedFlow(
                startTime = 600.milliseconds,
                duration = 500.milliseconds,
                onStep = { it },
            )
            .sample(powerInteractor.detailedWakefulness, ::Pair)
            .transform { (alpha, wakefulness) ->
                if (wakefulness.lastSleepReason == FOLD) {
                    emit(alpha)
                }
            }

    val notificationAlphaOnFold: Flow<Float> =
        transitionAnimationOnFold
            .sharedFlow(
                duration = 1100.milliseconds,
                onStep = { 0f },
                onFinish = { 1f },
            )
            .sample(powerInteractor.detailedWakefulness, ::Pair)
            .transform { (alpha, wakefulness) ->
                if (wakefulness.lastSleepReason == FOLD) {
                    emit(alpha)
                }
            }

    /** x-translation from the side of the screen for fold animation */
    fun enterFromSideTranslationX(translatePx: Int): Flow<StateToValue> {
        return transitionAnimationOnFold
            .sharedFlowWithState(
                startTime = 600.milliseconds,
                duration = 500.milliseconds,
                onStep = { translatePx + it * -translatePx },
                onFinish = { 0f },
                interpolator = EMPHASIZED_DECELERATE,
            )
            .sample(powerInteractor.detailedWakefulness, ::Pair)
            .transform { (stateToValue, wakefulness) ->
                if (wakefulness.lastSleepReason == FOLD) {
                    emit(stateToValue)
                }
            }
    }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest {
            isUdfpsEnrolledAndEnabled ->
            if (isUdfpsEnrolledAndEnabled) {
                shadeDependentFlows.transitionFlow(
                    flowWhenShadeIsExpanded = // fade in
                    transitionAnimation.sharedFlow(
                            duration = 300.milliseconds,
                            onStep = { it },
                            onCancel = { 1f },
                            onFinish = { 1f },
                        ),
                    flowWhenShadeIsNotExpanded = transitionAnimation.immediatelyTransitionTo(1f),
                )
            } else {
                shadeDependentFlows.transitionFlow(
                    flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
                    flowWhenShadeIsNotExpanded = // fade out
                    transitionAnimation.sharedFlow(
                            duration = 200.milliseconds,
                            onStep = { 1f - it },
                            onCancel = { 0f },
                            onFinish = { 0f },
                        ),
                )
            }
        }
}
