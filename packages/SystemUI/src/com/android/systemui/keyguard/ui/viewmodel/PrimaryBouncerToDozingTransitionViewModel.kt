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
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor.Companion.TO_DOZING_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down PRIMARY BOUNCER->DOZING transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class PrimaryBouncerToDozingTransitionViewModel
@Inject
constructor(
    private val blurConfig: BlurConfig,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition, PrimaryBouncerTransition {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_DOZING_DURATION,
                edge = Edge.create(from = Scenes.Bouncer, to = DOZING),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = PRIMARY_BOUNCER, to = DOZING))

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)

    override val deviceEntryParentViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest {
            isUdfpsEnrolledAndEnabled ->
            if (isUdfpsEnrolledAndEnabled) {
                transitionAnimation.immediatelyTransitionTo(1f)
            } else {
                emptyFlow()
            }
        }

    override val windowBlurRadius: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = TO_DOZING_DURATION,
            onStep = { step ->
                transitionProgressToBlurRadius(
                    starBlurRadius = blurConfig.maxBlurRadiusPx,
                    endBlurRadius = blurConfig.minBlurRadiusPx,
                    transitionProgress = step,
                )
            },
            onFinish = { blurConfig.minBlurRadiusPx },
        )
    override val notificationBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0.0f)
}
