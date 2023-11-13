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
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down AOD->LOCKSCREEN transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class AodToLockscreenTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        KeyguardTransitionAnimationFlow(
            transitionDuration = TO_LOCKSCREEN_DURATION,
            transitionFlow = interactor.aodToLockscreenTransition,
        )

    /** Ensure alpha is set to be visible */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.createFlow(
            duration = 500.milliseconds,
            onStart = { 1f },
            onStep = { 1f },
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsSupported.flatMapLatest { isUdfps ->
            if (isUdfps) {
                // fade in
                transitionAnimation.createFlow(
                    duration = 250.milliseconds,
                    onStep = { it },
                    onFinish = { 1f },
                )
            } else {
                // background view isn't visible, so return an empty flow
                emptyFlow()
            }
        }

    override val deviceEntryParentViewAlpha: Flow<Float> = lockscreenAlpha
}
