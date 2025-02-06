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
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor.Companion.TO_GLANCEABLE_HUB_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest

@SysUISingleton
class PrimaryBouncerToGlanceableHubTransitionViewModel
@Inject
constructor(
    blurConfig: BlurConfig,
    animationFlow: KeyguardTransitionAnimationFlow,
    communalSettingsInteractor: CommunalSettingsInteractor,
) : DeviceEntryIconTransition, PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(duration = TO_GLANCEABLE_HUB_DURATION, edge = Edge.INVALID)
            .setupWithoutSceneContainer(edge = Edge.create(PRIMARY_BOUNCER, GLANCEABLE_HUB))

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)

    override val windowBlurRadius: Flow<Float> =
        if (Flags.glanceableHubBlurredBackground()) {
            communalSettingsInteractor.communalBackground
                .filter { it != CommunalBackgroundType.BLUR }
                .flatMapLatest {
                    transitionAnimation.immediatelyTransitionTo(blurConfig.minBlurRadiusPx)
                }
        } else {
            transitionAnimation.immediatelyTransitionTo(blurConfig.minBlurRadiusPx)
        }

    override val notificationBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0.0f)
}
