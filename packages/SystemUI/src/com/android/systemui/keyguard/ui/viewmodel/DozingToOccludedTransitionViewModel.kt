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

import android.util.MathUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down DOZING->OCCLUDED transition into discrete steps for corresponding views to consume.
 */
@SysUISingleton
class DozingToOccludedTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow.setup(
            duration = FromAodTransitionInteractor.TO_OCCLUDED_DURATION,
            edge = Edge.create(from = DOZING, to = OCCLUDED),
        )

    /**
     * Fade out the lockscreen during a transition to OCCLUDED.
     *
     * This happens when pressing the power button while a SHOW_WHEN_LOCKED activity is on the top
     * of the task stack, as well as when the power button is double tapped on the LOCKSCREEN (the
     * first tap transitions to DOZING, the second cancels that transition and starts DOZING ->
     * OCCLUDED.
     */
    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var currentAlpha = 0f
        return transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            startTime = 100.milliseconds, // Wait for the light reveal to "hit" the LS elements.
            onStart = { currentAlpha = viewState.alpha() },
            onStep = { MathUtils.lerp(currentAlpha, 0f, it) },
            onCancel = { 0f },
        )
    }

    override val deviceEntryParentViewAlpha = transitionAnimation.immediatelyTransitionTo(0f)
}
