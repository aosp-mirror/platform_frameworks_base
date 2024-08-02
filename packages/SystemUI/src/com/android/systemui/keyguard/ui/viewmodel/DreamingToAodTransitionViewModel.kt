/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/** Breaks down DREAMING->AOD transition into discrete steps for corresponding views to consume. */
@ExperimentalCoroutinesApi
@SysUISingleton
class DreamingToAodTransitionViewModel
@Inject
constructor(
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow.setup(
            duration = FromDreamingTransitionInteractor.TO_AOD_DURATION,
            edge = Edge.create(from = DREAMING, to = AOD),
        )

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 300.milliseconds,
            onStep = { it },
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)

    override val deviceEntryParentViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest { udfpsEnrolledAndEnabled
            ->
            if (udfpsEnrolledAndEnabled) {
                transitionAnimation.sharedFlow(
                    duration = FromDreamingTransitionInteractor.TO_AOD_DURATION,
                    onStep = { it },
                    onCancel = { 1f },
                    onFinish = { 1f },
                )
            } else {
                emptyFlow()
            }
        }
}
