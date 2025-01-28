/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.transitions

import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * {@link GlanceableHubBlurProvider} helps provide a consistent blur experience across glanceable
 * hub transitions by defining a single point where both the exit and entry flows are defined. Note
 * that since these flows are driven by the specific transition animations, a singleton provider
 * cannot be used.
 */
class GlanceableHubBlurProvider
@Inject
constructor(
    transitionAnimation: KeyguardTransitionAnimationFlow.FlowBuilder,
    blurConfig: BlurConfig,
) {
    val exitBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(blurConfig.minBlurRadiusPx)

    val enterBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx)
}
