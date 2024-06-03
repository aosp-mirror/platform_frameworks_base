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
import com.android.systemui.keyguard.domain.interactor.FromGoneTransitionInteractor.Companion.TO_DREAMING_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

/** Breaks down GONE->DREAMING transition into discrete steps for corresponding views to consume. */
@SysUISingleton
class GoneToDreamingTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_DREAMING_DURATION,
                edge = Edge.create(from = Scenes.Gone, to = DREAMING),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = GONE, to = DREAMING),
            )

    /** Lockscreen views y-translation */
    fun lockscreenTranslationY(translatePx: Int): Flow<Float> {
        return transitionAnimation.sharedFlow(
            duration = 500.milliseconds,
            onStep = { it * translatePx },
            // Reset on cancel or finish
            onFinish = { 0f },
            onCancel = { 0f },
            interpolator = EMPHASIZED_ACCELERATE,
        )
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1f - it },
        )
}
