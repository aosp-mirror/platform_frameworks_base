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

import com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class OffToLockscreenTransitionViewModel
@Inject
constructor(animationFlow: KeyguardTransitionAnimationFlow) : DeviceEntryIconTransition {

    private val startTime = 300.milliseconds
    private val alphaDuration = 633.milliseconds
    val alphaStartAt = startTime / (alphaDuration + startTime)

    private val transitionAnimation =
        animationFlow.setup(
            duration = startTime + alphaDuration,
            edge = Edge.create(from = OFF, to = LOCKSCREEN),
        )

    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            startTime = startTime,
            duration = alphaDuration,
            interpolator = EMPHASIZED_ACCELERATE,
            onStep = { it },
        )

    val shortcutsAlpha: Flow<Float> = lockscreenAlpha

    override val deviceEntryParentViewAlpha: Flow<Float> = lockscreenAlpha

    val deviceEntryBackgroundViewAlpha: Flow<Float> = lockscreenAlpha
}
