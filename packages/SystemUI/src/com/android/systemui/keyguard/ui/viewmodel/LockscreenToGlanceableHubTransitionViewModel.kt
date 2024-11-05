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

import android.util.LayoutDirection
import com.android.app.animation.Interpolators.EMPHASIZED
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Breaks down LOCKSCREEN->GLANCEABLE_HUB transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToGlanceableHubTransitionViewModel
@Inject
constructor(
    configurationInteractor: ConfigurationInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
                edge = Edge.create(from = LOCKSCREEN, to = Scenes.Communal),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = LOCKSCREEN, to = GLANCEABLE_HUB))

    val keyguardAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 167.milliseconds,
            onStep = { 1f - it },
            onFinish = { 0f },
            onCancel = { 1f },
            name = "LOCKSCREEN->GLANCEABLE_HUB: keyguardAlpha",
        )

    // Show UMO as long as keyguard is not visible.
    val showUmo: Flow<Boolean> = keyguardAlpha.map { alpha -> alpha == 0f }

    val keyguardTranslationX: Flow<StateToValue> =
        configurationInteractor
            .directionalDimensionPixelSize(
                LayoutDirection.LTR,
                R.dimen.lockscreen_to_hub_transition_lockscreen_translation_x,
            )
            .flatMapLatest { translatePx: Int ->
                transitionAnimation.sharedFlowWithState(
                    duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
                    onStep = { value -> value * translatePx },
                    // Move notifications back to their original position since they can be
                    // accessed from the shade, and also keyguard elements in case the animation
                    // is cancelled.
                    onFinish = { 0f },
                    onCancel = { 0f },
                    interpolator = EMPHASIZED,
                    name = "LOCKSCREEN->GLANCEABLE_HUB: keyguardTranslationX",
                )
            }

    val notificationAlpha: Flow<Float> = keyguardAlpha

    val shortcutsAlpha: Flow<Float> = keyguardAlpha

    val statusBarAlpha: Flow<Float> = keyguardAlpha

    val notificationTranslationX: Flow<Float> =
        keyguardTranslationX.map { it.value }.filterNotNull()
}
