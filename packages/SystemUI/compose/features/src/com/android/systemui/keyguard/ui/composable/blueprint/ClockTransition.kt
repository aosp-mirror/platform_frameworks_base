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

package com.android.systemui.keyguard.ui.composable.blueprint

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.transitions
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.largeClockElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.smallClockElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.smartspaceElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockTransition.transitioningToLargeClock
import com.android.systemui.keyguard.ui.composable.blueprint.ClockTransition.transitioningToSmallClock
import com.android.systemui.keyguard.ui.composable.blueprint.WeatherClockElementKeys.largeWeatherClockElementKeyList
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.ClockFaceInTransition.Companion.CLOCK_IN_MILLIS
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.ClockFaceInTransition.Companion.CLOCK_IN_START_DELAY_MILLIS
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.ClockFaceOutTransition.Companion.CLOCK_OUT_MILLIS
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.SmartspaceMoveTransition.Companion.STATUS_AREA_MOVE_DOWN_MILLIS
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition.SmartspaceMoveTransition.Companion.STATUS_AREA_MOVE_UP_MILLIS

object ClockTransition {
    val defaultClockTransitions = transitions {
        from(ClockScenes.smallClockScene, to = ClockScenes.largeClockScene) {
            transitioningToLargeClock(largeClockElements = listOf(largeClockElementKey))
        }
        from(ClockScenes.largeClockScene, to = ClockScenes.smallClockScene) {
            transitioningToSmallClock(largeClockElements = listOf(largeClockElementKey))
        }
        from(ClockScenes.splitShadeLargeClockScene, to = ClockScenes.largeClockScene) {
            spec = tween(300, easing = LinearEasing)
        }

        from(WeatherClockScenes.largeClockScene, to = ClockScenes.smallClockScene) {
            transitioningToSmallClock(largeClockElements = largeWeatherClockElementKeyList)
        }

        from(ClockScenes.smallClockScene, to = WeatherClockScenes.largeClockScene) {
            transitioningToLargeClock(largeClockElements = largeWeatherClockElementKeyList)
        }

        from(
            WeatherClockScenes.largeClockScene,
            to = WeatherClockScenes.splitShadeLargeClockScene
        ) {
            spec = tween(300, easing = LinearEasing)
        }
    }

    private fun TransitionBuilder.transitioningToLargeClock(largeClockElements: List<ElementKey>) {
        spec = tween(durationMillis = STATUS_AREA_MOVE_UP_MILLIS.toInt())
        timestampRange(
            startMillis = CLOCK_IN_START_DELAY_MILLIS.toInt(),
            endMillis = (CLOCK_IN_START_DELAY_MILLIS + CLOCK_IN_MILLIS).toInt()
        ) {
            largeClockElements.forEach { fade(it) }
        }

        timestampRange(endMillis = CLOCK_OUT_MILLIS.toInt()) { fade(smallClockElementKey) }
        anchoredTranslate(smallClockElementKey, smartspaceElementKey)
    }

    private fun TransitionBuilder.transitioningToSmallClock(largeClockElements: List<ElementKey>) {
        spec = tween(durationMillis = STATUS_AREA_MOVE_DOWN_MILLIS.toInt())
        timestampRange(
            startMillis = CLOCK_IN_START_DELAY_MILLIS.toInt(),
            endMillis = (CLOCK_IN_START_DELAY_MILLIS + CLOCK_IN_MILLIS).toInt()
        ) {
            fade(smallClockElementKey)
        }

        timestampRange(endMillis = CLOCK_OUT_MILLIS.toInt()) {
            largeClockElements.forEach { fade(it) }
        }
        anchoredTranslate(smallClockElementKey, smartspaceElementKey)
    }
}

object ClockScenes {
    val smallClockScene = SceneKey("small-clock-scene")
    val largeClockScene = SceneKey("large-clock-scene")
    val splitShadeSmallClockScene = SceneKey("split-shade-small-clock-scene")
    val splitShadeLargeClockScene = SceneKey("split-shade-large-clock-scene")
}

object ClockElementKeys {
    val largeClockElementKey = ElementKey("large-clock")
    val smallClockElementKey = ElementKey("small-clock")
    val smartspaceElementKey = ElementKey("smart-space")
}

object WeatherClockScenes {
    val largeClockScene = SceneKey("large-weather-clock-scene")
    val splitShadeLargeClockScene = SceneKey("split-shade-large-weather-clock-scene")
}

object WeatherClockElementKeys {
    val timeElementKey = ElementKey("weather-large-clock-time")
    val dateElementKey = ElementKey("weather-large-clock-date")
    val weatherIconElementKey = ElementKey("weather-large-clock-weather-icon")
    val temperatureElementKey = ElementKey("weather-large-clock-temperature")
    val dndAlarmElementKey = ElementKey("weather-large-clock-dnd-alarm")
    val largeWeatherClockElementKeyList =
        listOf(
            timeElementKey,
            dateElementKey,
            weatherIconElementKey,
            temperatureElementKey,
            dndAlarmElementKey
        )
}
