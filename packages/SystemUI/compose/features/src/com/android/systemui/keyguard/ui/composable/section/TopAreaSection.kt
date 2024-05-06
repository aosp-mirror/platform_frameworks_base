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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.modifiers.thenIf
import com.android.systemui.compose.modifiers.sysuiResTag
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
                KeyguardClockViewModel.ClockLayout.WEATHER_LARGE_CLOCK ->
                    WeatherClockScenes.largeClockScene
                KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_WEATHER_LARGE_CLOCK ->
                    WeatherClockScenes.splitShadeLargeClockScene
            }

        SceneTransitionLayout(
            modifier = modifier.sysuiResTag("keyguard_clock_container"),
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

            scene(WeatherClockScenes.largeClockScene) { WeatherLargeClockWithSmartSpace() }

            scene(WeatherClockScenes.splitShadeLargeClockScene) {
                WeatherLargeClockWithSmartSpace(modifier = Modifier.fillMaxWidth(0.5f))
            }
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
            with(mediaCarouselSection) { KeyguardMediaCarousel() }
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
    private fun SceneScope.WeatherLargeClockWithSmartSpace(modifier: Modifier = Modifier) {
        val burnIn = rememberBurnIn(clockInteractor)
        val isLargeClockVisible by clockViewModel.isLargeClockVisible.collectAsState()
        val currentClockState = clockViewModel.currentClock.collectAsState()

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
