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
import com.android.systemui.keyguard.domain.interactor.FromOccludedTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@SysUISingleton
class OccludedToPrimaryBouncerTransitionViewModel
@Inject
constructor(
    shadeDependentFlows: ShadeDependentFlows,
    blurConfig: BlurConfig,
    animationFlow: KeyguardTransitionAnimationFlow,
) : PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromOccludedTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.INVALID,
            )
            .setupWithoutSceneContainer(edge = Edge.create(OCCLUDED, PRIMARY_BOUNCER))

    override val windowBlurRadius: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsExpanded =
                if (Flags.notificationShadeBlur()) {
                    transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx)
                } else {
                    emptyFlow()
                },
            flowWhenShadeIsNotExpanded =
                transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx),
        )

    override val notificationBlurRadius: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsExpanded =
                transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx),
            flowWhenShadeIsNotExpanded = emptyFlow(),
        )
}
