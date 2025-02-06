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

import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromAlternateBouncerTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.transitions.TO_BOUNCER_FADE_FRACTION
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Breaks down ALTERNATE BOUNCER->PRIMARY BOUNCER transition into discrete steps for corresponding
 * views to consume.
 */
@SysUISingleton
class AlternateBouncerToPrimaryBouncerTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
    blurConfig: BlurConfig,
    shadeDependentFlows: ShadeDependentFlows,
) : DeviceEntryIconTransition, PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromAlternateBouncerTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.create(from = ALTERNATE_BOUNCER, to = Scenes.Bouncer),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = ALTERNATE_BOUNCER, to = PRIMARY_BOUNCER)
            )

    private val alphaForAnimationStep: (Float) -> Float =
        when {
            SceneContainerFlag.isEnabled -> { step ->
                    1f - Math.min((step / TO_BOUNCER_FADE_FRACTION), 1f)
                }
            else -> { step -> 1f - step }
        }

    private val alphaFlow =
        transitionAnimation.sharedFlow(
            duration = FromAlternateBouncerTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
            onStep = alphaForAnimationStep,
        )

    val lockscreenAlpha: Flow<Float> = if (Flags.bouncerUiRevamp()) alphaFlow else emptyFlow()

    val notificationAlpha: Flow<Float> =
        if (Flags.bouncerUiRevamp()) {
            shadeDependentFlows.transitionFlow(
                flowWhenShadeIsNotExpanded = lockscreenAlpha,
                flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(1f),
            )
        } else {
            alphaFlow
        }

    override val notificationBlurRadius: Flow<Float> =
        if (Flags.bouncerUiRevamp()) {
            shadeDependentFlows.transitionFlow(
                flowWhenShadeIsNotExpanded = emptyFlow(),
                flowWhenShadeIsExpanded =
                    transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx),
            )
        } else {
            emptyFlow<Float>()
        }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)

    override val windowBlurRadius: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsExpanded =
                if (Flags.notificationShadeBlur()) {
                    transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx)
                } else {
                    emptyFlow()
                },
            flowWhenShadeIsNotExpanded =
                transitionAnimation.sharedFlow(
                    duration = FromAlternateBouncerTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                    onStep = { step ->
                        transitionProgressToBlurRadius(
                            starBlurRadius = blurConfig.minBlurRadiusPx,
                            endBlurRadius = blurConfig.maxBlurRadiusPx,
                            transitionProgress = step,
                        )
                    },
                    onFinish = { blurConfig.maxBlurRadiusPx },
                ),
        )
}
