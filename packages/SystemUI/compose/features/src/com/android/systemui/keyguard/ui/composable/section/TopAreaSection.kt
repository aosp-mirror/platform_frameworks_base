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

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.modifiers.thenIf
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.largeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.smallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeLargeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeSmallClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockTransition
import com.android.systemui.keyguard.ui.composable.blueprint.WeatherClockScenes
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
    private val weatherClockSection: WeatherClockSection,
    private val clockInteractor: KeyguardClockInteractor,
) {
    @Composable
    fun SceneScope.DefaultClockLayout(
        smartSpacePaddingTop: (Resources) -> Int,
        modifier: Modifier = Modifier,
    ) {
        val currentClockLayout by clockViewModel.currentClockLayout.collectAsStateWithLifecycle()
        val hasCustomPositionUpdatedAnimation by
            clockViewModel.hasCustomPositionUpdatedAnimation.collectAsStateWithLifecycle()
        val currentScene =
            when (currentClockLayout) {
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_LARGE_CLOCK ->
                    splitShadeLargeClockScene
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_SMALL_CLOCK ->
                    splitShadeSmallClockScene
                KeyguardClockViewModel.ClockLayout.LARGE_CLOCK -> largeClockScene
                KeyguardClockViewModel.ClockLayout.SMALL_CLOCK -> smallClockScene
                KeyguardClockViewModel.ClockLayout.WEATHER_LARGE_CLOCK ->
                    WeatherClockScenes.largeClockScene
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_WEATHER_LARGE_CLOCK ->
                    WeatherClockScenes.splitShadeLargeClockScene
            }

        val state = remember {
            MutableSceneTransitionLayoutState(
                currentScene,
                ClockTransition.defaultClockTransitions,
                enableInterruptions = false,
            )
        }

        // Update state whenever currentSceneKey has changed.
        LaunchedEffect(state, currentScene) {
            if (currentScene != state.transitionState.currentScene) {
                state.setTargetScene(currentScene, animationScope = this)
            }
        }

        Column(modifier) {
            SceneTransitionLayout(state) {
                scene(splitShadeLargeClockScene) {
                    LargeClockWithSmartSpace(
                        smartSpacePaddingTop = smartSpacePaddingTop,
                        shouldOffSetClockToOneHalf = !hasCustomPositionUpdatedAnimation
                    )
                }

                scene(splitShadeSmallClockScene) {
                    SmallClockWithSmartSpace(
                        smartSpacePaddingTop = smartSpacePaddingTop,
                        modifier = Modifier.fillMaxWidth(0.5f),
                    )
                }

                scene(smallClockScene) {
                    SmallClockWithSmartSpace(
                        smartSpacePaddingTop = smartSpacePaddingTop,
                    )
                }

                scene(largeClockScene) {
                    LargeClockWithSmartSpace(
                        smartSpacePaddingTop = smartSpacePaddingTop,
                    )
                }

                scene(WeatherClockScenes.largeClockScene) {
                    WeatherLargeClockWithSmartSpace(
                        smartSpacePaddingTop = smartSpacePaddingTop,
                    )
                }

                scene(WeatherClockScenes.splitShadeLargeClockScene) {
                    WeatherLargeClockWithSmartSpace(
                        smartSpacePaddingTop = smartSpacePaddingTop,
                        modifier = Modifier.fillMaxWidth(0.5f),
                    )
                }
            }
            with(mediaCarouselSection) { KeyguardMediaCarousel() }
        }
    }

    @Composable
    private fun SceneScope.SmallClockWithSmartSpace(
        smartSpacePaddingTop: (Resources) -> Int,
        modifier: Modifier = Modifier,
    ) {
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
                    smartSpacePaddingTop = smartSpacePaddingTop,
                )
            }
        }
    }

    @Composable
    private fun SceneScope.LargeClockWithSmartSpace(
        smartSpacePaddingTop: (Resources) -> Int,
        shouldOffSetClockToOneHalf: Boolean = false,
    ) {
        val burnIn = rememberBurnIn(clockInteractor)
        val isLargeClockVisible by clockViewModel.isLargeClockVisible.collectAsStateWithLifecycle()

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
                    smartSpacePaddingTop = smartSpacePaddingTop,
                )
            }
            with(clockSection) {
                LargeClock(
                    burnInParams = burnIn.parameters,
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

    @Composable
    private fun SceneScope.WeatherLargeClockWithSmartSpace(
        smartSpacePaddingTop: (Resources) -> Int,
        modifier: Modifier = Modifier,
    ) {
        val burnIn = rememberBurnIn(clockInteractor)
        val isLargeClockVisible by clockViewModel.isLargeClockVisible.collectAsStateWithLifecycle()
        val currentClockState = clockViewModel.currentClock.collectAsStateWithLifecycle()

        LaunchedEffect(isLargeClockVisible) {
            if (isLargeClockVisible) {
                burnIn.onSmallClockTopChanged(null)
            }
        }

        Column(modifier = modifier) {
            val currentClock = currentClockState.value ?: return@Column
            with(weatherClockSection) {
                Time(
                    clock = currentClock,
                    burnInParams = burnIn.parameters,
                )
            }
            val density = LocalDensity.current
            val context = LocalContext.current

            with(smartSpaceSection) {
                SmartSpace(
                    burnInParams = burnIn.parameters,
                    onTopChanged = burnIn.onSmartspaceTopChanged,
                    smartSpacePaddingTop = smartSpacePaddingTop,
                    modifier =
                        Modifier.heightIn(
                            min = getDimen(context, "enhanced_smartspace_height", density)
                        )
                )
            }
            with(weatherClockSection) {
                LargeClockSectionBelowSmartspace(
                    burnInParams = burnIn.parameters,
                    clock = currentClock,
                )
            }
        }
    }

    /*
     * Use this function to access dimen which cannot be access by R.dimen directly
     * Currently use to access dimen from BcSmartspace
     * @param name Name of resources
     * @param density Density required to convert dimen from Int To Dp
     */
    private fun getDimen(context: Context, name: String, density: Density): Dp {
        val res = context.packageManager.getResourcesForApplication(context.packageName)
        val id = res.getIdentifier(name, "dimen", context.packageName)
        var dimen: Dp
        with(density) { dimen = (if (id == 0) 0 else res.getDimensionPixelSize(id)).toDp() }
        return dimen
    }
}
