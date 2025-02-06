/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.app.animation.Interpolators.EMPHASIZED
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down DREAMING->LOCKSCREEN transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class DreamingToLockscreenTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_LOCKSCREEN_DURATION,
            edge = Edge.create(from = DREAMING, to = LOCKSCREEN),
        )

    /** Dream overlay y-translation on exit */
    fun dreamOverlayTranslationY(translatePx: Int): Flow<Float> {
        return transitionAnimation.sharedFlow(
            duration = TO_LOCKSCREEN_DURATION,
            onStep = { it * translatePx },
            interpolator = EMPHASIZED,
        )
    }

    /** Dream overlay views alpha - fade out */
    val dreamOverlayAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1f - it },
        )

    /** Lockscreen views y-translation */
    fun lockscreenTranslationY(translatePx: Int): Flow<Float> {
        return transitionAnimation.sharedFlow(
            duration = TO_LOCKSCREEN_DURATION,
            onStep = { value -> -translatePx + value * translatePx },
            // Reset on cancel or finish
            onFinish = { 0f },
            onCancel = { 0f },
            interpolator = EMPHASIZED,
        )
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            startTime = 233.milliseconds,
            duration = 250.milliseconds,
            onStep = { it },
        )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            startTime = 233.milliseconds,
            duration = 250.milliseconds,
            onStep = { it },
            onCancel = { 0f },
        )

    val deviceEntryBackgroundViewAlpha = transitionAnimation.immediatelyTransitionTo(1f)
    override val deviceEntryParentViewAlpha =
        transitionAnimation.sharedFlow(
            startTime = 233.milliseconds,
            duration = 250.milliseconds,
            onCancel = { 1f },
            onStep = { it },
        )
}
