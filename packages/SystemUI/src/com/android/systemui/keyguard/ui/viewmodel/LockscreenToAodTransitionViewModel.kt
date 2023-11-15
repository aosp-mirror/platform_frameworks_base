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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down LOCKSCREEN->AOD transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToAodTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    shadeDependentFlows: ShadeDependentFlows,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        KeyguardTransitionAnimationFlow(
            transitionDuration = FromLockscreenTransitionInteractor.TO_AOD_DURATION,
            transitionFlow = interactor.lockscreenToAodTransition,
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
            flowWhenShadeIsNotExpanded =
                transitionAnimation.createFlow(
                    duration = 300.milliseconds,
                    onStep = { 1 - it },
                    onFinish = { 0f },
                ),
        )
    override val deviceEntryParentViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest {
            isUdfpsEnrolledAndEnabled ->
            if (isUdfpsEnrolledAndEnabled) {
                shadeDependentFlows.transitionFlow(
                    flowWhenShadeIsExpanded = // fade in
                    transitionAnimation.createFlow(
                            duration = 300.milliseconds,
                            onStep = { it },
                            onFinish = { 1f },
                        ),
                    flowWhenShadeIsNotExpanded = transitionAnimation.immediatelyTransitionTo(1f),
                )
            } else {
                shadeDependentFlows.transitionFlow(
                    flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
                    flowWhenShadeIsNotExpanded = // fade out
                    transitionAnimation.createFlow(
                            duration = 200.milliseconds,
                            onStep = { 1f - it },
                            onFinish = { 0f },
                        ),
                )
            }
        }
}
