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

package com.android.systemui.shared.clocks

import com.android.systemui.animation.GSFAxes
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.AxisType
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFontAxis
import com.android.systemui.plugins.clocks.ClockFontAxis.Companion.merge
import com.android.systemui.plugins.clocks.ClockFontAxisSetting
import com.android.systemui.plugins.clocks.ClockSettings
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.shared.clocks.view.FlexClockView
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone

/** Controller for the default flex clock */
class FlexClockController(private val clockCtx: ClockContext) : ClockController {
    override val smallClock =
        FlexClockFaceController(
            clockCtx.copy(messageBuffer = clockCtx.messageBuffers.smallClockMessageBuffer),
            isLargeClock = false,
        )

    override val largeClock =
        FlexClockFaceController(
            clockCtx.copy(messageBuffer = clockCtx.messageBuffers.largeClockMessageBuffer),
            isLargeClock = true,
        )

    override val config: ClockConfig by lazy {
        ClockConfig(
            DEFAULT_CLOCK_ID,
            clockCtx.resources.getString(R.string.clock_default_name),
            clockCtx.resources.getString(R.string.clock_default_description),
        )
    }

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false
                set(value) {
                    field = value
                    val view = largeClock.view as FlexClockView
                    view.isReactiveTouchInteractionEnabled = value
                }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                smallClock.events.onTimeZoneChanged(timeZone)
                largeClock.events.onTimeZoneChanged(timeZone)
            }

            override fun onTimeFormatChanged(is24Hr: Boolean) {
                smallClock.events.onTimeFormatChanged(is24Hr)
                largeClock.events.onTimeFormatChanged(is24Hr)
            }

            override fun onLocaleChanged(locale: Locale) {
                smallClock.events.onLocaleChanged(locale)
                largeClock.events.onLocaleChanged(locale)
            }

            override fun onWeatherDataChanged(data: WeatherData) {
                smallClock.events.onWeatherDataChanged(data)
                largeClock.events.onWeatherDataChanged(data)
            }

            override fun onAlarmDataChanged(data: AlarmData) {
                smallClock.events.onAlarmDataChanged(data)
                largeClock.events.onAlarmDataChanged(data)
            }

            override fun onZenDataChanged(data: ZenData) {
                smallClock.events.onZenDataChanged(data)
                largeClock.events.onZenDataChanged(data)
            }

            override fun onFontAxesChanged(axes: List<ClockFontAxisSetting>) {
                val fontAxes = getDefaultAxes(clockCtx.settings).merge(axes).map { it.toSetting() }
                smallClock.events.onFontAxesChanged(fontAxes)
                largeClock.events.onFontAxesChanged(fontAxes)
            }
        }

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        events.onFontAxesChanged(clockCtx.settings.axes)
        smallClock.run {
            events.onThemeChanged(theme.copy(isDarkTheme = isDarkTheme))
            animations.doze(dozeFraction)
            animations.fold(foldFraction)
            events.onTimeTick()
        }

        largeClock.run {
            events.onThemeChanged(theme.copy(isDarkTheme = isDarkTheme))
            animations.doze(dozeFraction)
            animations.fold(foldFraction)
            events.onTimeTick()
        }
    }

    override fun dump(pw: PrintWriter) {}

    companion object {
        fun getDefaultAxes(settings: ClockSettings): List<ClockFontAxis> {
            return if (settings.clockId == FLEX_CLOCK_ID) {
                FONT_AXES.merge(LEGACY_FLEX_SETTINGS)
            } else FONT_AXES
        }

        private val FONT_AXES =
            listOf(
                ClockFontAxis(
                    key = GSFAxes.WEIGHT,
                    type = AxisType.Float,
                    minValue = 25f,
                    currentValue = 400f,
                    maxValue = 1000f,
                    name = "Weight",
                    description = "Glyph Weight",
                ),
                ClockFontAxis(
                    key = GSFAxes.WIDTH,
                    type = AxisType.Float,
                    minValue = 25f,
                    currentValue = 85f,
                    maxValue = 151f,
                    name = "Width",
                    description = "Glyph Width",
                ),
                ClockFontAxis(
                    key = GSFAxes.ROUND,
                    type = AxisType.Boolean,
                    minValue = 0f,
                    currentValue = 0f,
                    maxValue = 100f,
                    name = "Round",
                    description = "Glyph Roundness",
                ),
                ClockFontAxis(
                    key = GSFAxes.SLANT,
                    type = AxisType.Boolean,
                    minValue = 0f,
                    currentValue = 0f,
                    maxValue = -10f,
                    name = "Slant",
                    description = "Glyph Slant",
                ),
            )

        private val LEGACY_FLEX_SETTINGS =
            listOf(
                ClockFontAxisSetting(GSFAxes.WEIGHT, 600f),
                ClockFontAxisSetting(GSFAxes.WIDTH, 100f),
                ClockFontAxisSetting(GSFAxes.ROUND, 100f),
                ClockFontAxisSetting(GSFAxes.SLANT, 0f),
            )
    }
}
