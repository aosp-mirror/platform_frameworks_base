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
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down PRIMARY BOUNCER->AOD transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class PrimaryBouncerToAodTransitionViewModel
@Inject
constructor(
    private val blurConfig: BlurConfig,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition, PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromPrimaryBouncerTransitionInteractor.TO_AOD_DURATION,
                edge = Edge.create(from = Scenes.Bouncer, to = AOD),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = PRIMARY_BOUNCER, to = AOD))

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)

    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = FromPrimaryBouncerTransitionInteractor.TO_AOD_DURATION,
            onStep = { it },
        )

    override val deviceEntryParentViewAlpha: Flow<Float> =
        deviceEntryUdfpsInteractor.isUdfpsEnrolledAndEnabled.flatMapLatest {
            isUdfpsEnrolledAndEnabled ->
            if (isUdfpsEnrolledAndEnabled) {
                transitionAnimation.sharedFlow(
                    duration = 300.milliseconds,
                    onStep = { it },
                    onCancel = { 1f },
                    onFinish = { 1f },
                )
            } else {
                emptyFlow()
            }
        }

    override val windowBlurRadius: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = FromPrimaryBouncerTransitionInteractor.TO_AOD_DURATION,
            onStep = { step ->
                transitionProgressToBlurRadius(
                    starBlurRadius = blurConfig.maxBlurRadiusPx,
                    endBlurRadius = blurConfig.minBlurRadiusPx,
                    transitionProgress = step,
                )
            },
            onFinish = { blurConfig.minBlurRadiusPx },
        )
}
