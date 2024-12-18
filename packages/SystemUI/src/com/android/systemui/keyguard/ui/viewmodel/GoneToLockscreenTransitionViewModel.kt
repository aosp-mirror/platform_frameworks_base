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
import com.android.systemui.keyguard.domain.interactor.FromGoneTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class GoneToLockscreenTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_LOCKSCREEN_DURATION,
                edge = Edge.create(from = Scenes.Gone, to = LOCKSCREEN),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = GONE, to = LOCKSCREEN),
            )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { it },
            onCancel = { 1f },
        )

    val lockscreenAlpha: Flow<Float> = shortcutsAlpha

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)
}
