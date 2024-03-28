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

package com.android.systemui.keyguard.ui.composable.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.modifiers.thenIf
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.largeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.smallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeLargeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeSmallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockTransition
import com.android.systemui.keyguard.ui.composable.blueprint.rememberBurnIn
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import javax.inject.Inject

class TopAreaSection
@Inject
constructor(
    private val clockViewModel: KeyguardClockViewModel,
    private val smartSpaceSection: SmartSpaceSection,
    private val mediaCarouselSection: MediaCarouselSection,
    private val clockSection: DefaultClockSection,
    private val clockInteractor: KeyguardClockInteractor,
) {
    @Composable
    fun DefaultClockLayout(
        modifier: Modifier = Modifier,
    ) {
        val currentClockLayout by clockViewModel.currentClockLayout.collectAsState()
        val hasCustomPositionUpdatedAnimation by
            clockViewModel.hasCustomPositionUpdatedAnimation.collectAsState()
        val currentScene =
            when (currentClockLayout) {
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_LARGE_CLOCK ->
                    splitShadeLargeClockScene
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_SMALL_CLOCK ->
                    splitShadeSmallClockScene
                KeyguardClockViewModel.ClockLayout.LARGE_CLOCK -> largeClockScene
                KeyguardClockViewModel.ClockLayout.SMALL_CLOCK -> smallClockScene
            }

        SceneTransitionLayout(
            modifier = modifier,
            currentScene = currentScene,
            onChangeScene = {},
            transitions = ClockTransition.defaultClockTransitions,
            enableInterruptions = false,
        ) {
            scene(splitShadeLargeClockScene) {
                LargeClockWithSmartSpace(
                    shouldOffSetClockToOneHalf = !hasCustomPositionUpdatedAnimation
                )
            }

            scene(splitShadeSmallClockScene) {
                SmallClockWithSmartSpace(modifier = Modifier.fillMaxWidth(0.5f))
            }

            scene(smallClockScene) { SmallClockWithSmartSpace() }

            scene(largeClockScene) { LargeClockWithSmartSpace() }
        }
    }

    @Composable
    private fun SceneScope.SmallClockWithSmartSpace(modifier: Modifier = Modifier) {
        val burnIn = rememberBurnIn(clockInteractor)

        Column(modifier = modifier) {
            with(clockSection) {
                SmallClock(
                    burnInParams = burnIn.parameters,
                    onTopChanged = burnIn.onSmallClockTopChanged,
                    modifier = Modifier.wrapContentSize()
                )
            }
            with(smartSpaceSection) {
                SmartSpace(
                    burnInParams = burnIn.parameters,
                    onTopChanged = burnIn.onSmartspaceTopChanged,
                )
            }
            with(mediaCarouselSection) { MediaCarousel() }
        }
    }

    @Composable
    private fun SceneScope.LargeClockWithSmartSpace(shouldOffSetClockToOneHalf: Boolean = false) {
        val burnIn = rememberBurnIn(clockInteractor)
        val isLargeClockVisible by clockViewModel.isLargeClockVisible.collectAsState()

        LaunchedEffect(isLargeClockVisible) {
            if (isLargeClockVisible) {
                burnIn.onSmallClockTopChanged(null)
            }
        }

        Column {
            with(smartSpaceSection) {
                SmartSpace(
                    burnInParams = burnIn.parameters,
                    onTopChanged = burnIn.onSmartspaceTopChanged,
                )
            }
            with(clockSection) {
                LargeClock(
                    modifier =
                        Modifier.fillMaxSize().thenIf(shouldOffSetClockToOneHalf) {
                            // If we do not have a custom position animation, we want
                            // the clock to be on one half of the screen.
                            Modifier.offset {
                                IntOffset(
                                    x = -clockSection.getClockCenteringDistance().toInt(),
                                    y = 0,
                                )
                            }
                        }
                )
            }
        }
    }
}
