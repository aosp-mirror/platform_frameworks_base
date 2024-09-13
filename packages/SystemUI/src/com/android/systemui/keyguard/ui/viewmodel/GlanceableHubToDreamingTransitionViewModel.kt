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
import com.android.app.animation.Interpolators
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class GlanceableHubToDreamingTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
    configurationInteractor: ConfigurationInteractor,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FROM_GLANCEABLE_HUB_DURATION,
                edge = Edge.create(from = Scenes.Communal, to = DREAMING),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = GLANCEABLE_HUB, to = DREAMING),
            )

    val dreamOverlayAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 167.milliseconds,
            startTime = 167.milliseconds,
            onStep = { it },
            name = "GLANCEABLE_HUB->DREAMING: dreamOverlayAlpha",
        )

    val dreamOverlayTranslationX: Flow<Float> =
        configurationInteractor
            .directionalDimensionPixelSize(
                LayoutDirection.LTR,
                R.dimen.hub_to_dreaming_transition_dream_overlay_translation_x
            )
            .flatMapLatest { translatePx: Int ->
                transitionAnimation.sharedFlow(
                    duration = FROM_GLANCEABLE_HUB_DURATION,
                    onStep = { value -> -translatePx + value * translatePx },
                    interpolator = Interpolators.EMPHASIZED,
                    onCancel = { -translatePx.toFloat() },
                    name = "GLANCEABLE_HUB->LOCKSCREEN: dreamOverlayTranslationX"
                )
            }

    // Show UMO until transition finishes.
    val showUmo: Flow<Boolean> =
        transitionAnimation
            .sharedFlow(
                duration = FROM_GLANCEABLE_HUB_DURATION,
                onStep = { it },
                onCancel = { 0f },
                onFinish = { 1f },
            )
            .map { step -> step != 1f }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 167.milliseconds,
            onStep = { 1 - it },
            onCancel = { 0f },
            onFinish = { 0f },
        )

    private companion object {
        val FROM_GLANCEABLE_HUB_DURATION = 1.seconds
    }
}
