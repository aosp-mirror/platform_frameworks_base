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

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.padding
import com.android.systemui.customization.R as customizationR
import com.android.systemui.keyguard.ui.composable.blueprint.WeatherClockElementKeys
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.plugins.clocks.ClockController
import javax.inject.Inject

/** Provides small clock and large clock composables for the weather clock layout. */
class WeatherClockSection
@Inject
constructor(
    private val viewModel: KeyguardClockViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
) {
    @Composable
    fun SceneScope.Time(
        clock: ClockController,
        burnInParams: BurnInParameters,
    ) {
        Row(
            modifier =
                Modifier.padding(
                        horizontal = dimensionResource(customizationR.dimen.clock_padding_start)
                    )
                    .burnInAware(aodBurnInViewModel, burnInParams, isClock = true)
        ) {
            WeatherElement(
                weatherClockElementViewId = customizationR.id.weather_clock_time,
                clock = clock,
                elementKey = WeatherClockElementKeys.timeElementKey,
            )
        }
    }

    @Composable
    private fun SceneScope.Date(
        clock: ClockController,
        modifier: Modifier = Modifier,
    ) {
        WeatherElement(
            weatherClockElementViewId = customizationR.id.weather_clock_date,
            clock = clock,
            elementKey = WeatherClockElementKeys.dateElementKey,
            modifier = modifier,
        )
    }

    @Composable
    private fun SceneScope.Weather(
        clock: ClockController,
        modifier: Modifier = Modifier,
    ) {
        WeatherElement(
            weatherClockElementViewId = customizationR.id.weather_clock_weather_icon,
            clock = clock,
            elementKey = WeatherClockElementKeys.weatherIconElementKey,
            modifier = modifier.wrapContentSize(),
        )
    }

    @Composable
    private fun SceneScope.DndAlarmStatus(
        clock: ClockController,
        modifier: Modifier = Modifier,
    ) {
        WeatherElement(
            weatherClockElementViewId = customizationR.id.weather_clock_alarm_dnd,
            clock = clock,
            elementKey = WeatherClockElementKeys.dndAlarmElementKey,
            modifier = modifier.wrapContentSize(),
        )
    }

    @Composable
    private fun SceneScope.Temperature(
        clock: ClockController,
        modifier: Modifier = Modifier,
    ) {
        WeatherElement(
            weatherClockElementViewId = customizationR.id.weather_clock_temperature,
            clock = clock,
            elementKey = WeatherClockElementKeys.temperatureElementKey,
            modifier = modifier.wrapContentSize(),
        )
    }

    @Composable
    private fun SceneScope.WeatherElement(
        weatherClockElementViewId: Int,
        clock: ClockController,
        elementKey: ElementKey,
        modifier: Modifier = Modifier,
    ) {
        MovableElement(key = elementKey, modifier) {
            content {
                AndroidView(
                    factory = {
                        try {
                            val view =
                                clock.largeClock.layout.views.first {
                                    it.id == weatherClockElementViewId
                                }
                            (view.parent as? ViewGroup)?.removeView(view)
                            view
                        } catch (e: NoSuchElementException) {
                            View(it)
                        }
                    },
                    update = {},
                    modifier = modifier
                )
            }
        }
    }

    @Composable
    fun SceneScope.LargeClockSectionBelowSmartspace(
        burnInParams: BurnInParameters,
        clock: ClockController,
    ) {
        Row(
            modifier =
                Modifier.height(IntrinsicSize.Max)
                    .padding(
                        horizontal = dimensionResource(customizationR.dimen.clock_padding_start)
                    )
                    .burnInAware(aodBurnInViewModel, burnInParams, isClock = true)
        ) {
            Date(clock = clock, modifier = Modifier.wrapContentSize())
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .padding(
                            start = dimensionResource(customizationR.dimen.clock_padding_start)
                        )
            ) {
                Weather(clock = clock, modifier = Modifier.align(Alignment.TopStart))
                Temperature(clock = clock, modifier = Modifier.align(Alignment.BottomEnd))
                DndAlarmStatus(clock = clock, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}
