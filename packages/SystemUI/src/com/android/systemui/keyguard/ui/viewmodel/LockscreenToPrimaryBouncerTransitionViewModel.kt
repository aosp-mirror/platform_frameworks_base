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

import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.transitions.TO_BOUNCER_FADE_FRACTION
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Breaks down LOCKSCREEN->PRIMARY BOUNCER transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class LockscreenToPrimaryBouncerTransitionViewModel
@Inject
constructor(
    private val blurConfig: BlurConfig,
    shadeDependentFlows: ShadeDependentFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition, PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.create(from = LOCKSCREEN, to = Scenes.Bouncer),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = LOCKSCREEN, to = PRIMARY_BOUNCER))

    private val alphaForAnimationStep: (Float) -> Float =
        when {
            SceneContainerFlag.isEnabled -> { step ->
                    1f - Math.min((step / TO_BOUNCER_FADE_FRACTION), 1f)
                }
            else -> { step -> 1f - step }
        }

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
            onStep = alphaForAnimationStep,
        )

    val lockscreenAlpha: Flow<Float> = shortcutsAlpha

    val notificationAlpha: Flow<Float> =
        if (Flags.bouncerUiRevamp()) {
            shadeDependentFlows.transitionFlow(
                flowWhenShadeIsNotExpanded = lockscreenAlpha,
                flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(1f),
            )
        } else {
            lockscreenAlpha
        }

    override val notificationBlurRadius: Flow<Float> =
        if (Flags.bouncerUiRevamp()) {
            shadeDependentFlows.transitionFlow(
                flowWhenShadeIsNotExpanded = emptyFlow(),
                flowWhenShadeIsExpanded =
                    transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx),
            )
        } else {
            emptyFlow()
        }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsNotExpanded =
                transitionAnimation.sharedFlow(
                    duration = 250.milliseconds,
                    onStep = { 1f - it },
                    onCancel = { 0f },
                    onFinish = { 0f },
                ),
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
        )
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
                    duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                    onStep = {
                        transitionProgressToBlurRadius(
                            starBlurRadius = blurConfig.minBlurRadiusPx,
                            endBlurRadius = blurConfig.maxBlurRadiusPx,
                            transitionProgress = it,
                        )
                    },
                ),
        )
}
