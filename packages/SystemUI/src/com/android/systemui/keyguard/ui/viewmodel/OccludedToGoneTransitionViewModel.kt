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
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/** Breaks down OCCLUDED->GONE transition into discrete steps for corresponding views to consume. */
@ExperimentalCoroutinesApi
@SysUISingleton
class OccludedToGoneTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = DEFAULT_DURATION,
                edge = Edge.create(from = OCCLUDED, to = Scenes.Gone),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = OCCLUDED, to = GONE),
            )

    fun notificationAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var currentAlpha = 0f
        return transitionAnimation.sharedFlow(
            duration = DEFAULT_DURATION,
            onStart = { currentAlpha = viewState.alpha() },
            onStep = { currentAlpha },
            onFinish = { 1f },
        )
    }

    companion object {
        val DEFAULT_DURATION = 300.milliseconds
    }
}
