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
import com.android.systemui.keyguard.domain.interactor.FromGlanceableHubTransitionInteractor.Companion.TO_OCCLUDED_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class GlanceableHubToOccludedTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_OCCLUDED_DURATION,
                edge = Edge.create(from = Scenes.Communal, to = OCCLUDED),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = GLANCEABLE_HUB, to = OCCLUDED))

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)
}
