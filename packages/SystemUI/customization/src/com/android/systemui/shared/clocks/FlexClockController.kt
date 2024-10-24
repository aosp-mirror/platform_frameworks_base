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

import android.content.Context
import android.content.res.Resources
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockReactiveSetting
import com.android.systemui.plugins.clocks.ThemeConfig
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.shared.clocks.view.FlexClockView
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone

/** Controller for the default flex clock */
class FlexClockController(
    private val ctx: Context,
    private val resources: Resources,
    private val assets: AssetLoader, // TODO(b/364680879): Remove and replace w/ resources
    val design: ClockDesign, // TODO(b/364680879): Remove when done inlining
    val messageBuffers: ClockMessageBuffers?,
) : ClockController {
    override val smallClock = run {
        val buffer = messageBuffers?.smallClockMessageBuffer ?: LogUtil.DEFAULT_MESSAGE_BUFFER
        FlexClockFaceController(
            ctx,
            resources,
            assets.copy(messageBuffer = buffer),
            design.small ?: design.large!!,
            false,
            buffer,
        )
    }

    override val largeClock = run {
        val buffer = messageBuffers?.largeClockMessageBuffer ?: LogUtil.DEFAULT_MESSAGE_BUFFER
        FlexClockFaceController(
            ctx,
            resources,
            assets.copy(messageBuffer = buffer),
            design.large ?: design.small!!,
            true,
            buffer,
        )
    }

    override val config: ClockConfig by lazy {
        ClockConfig(
            DEFAULT_CLOCK_ID,
            resources.getString(R.string.clock_default_name),
            resources.getString(R.string.clock_default_description),
            isReactiveToTone = true,
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

            override fun onReactiveAxesChanged(axes: List<ClockReactiveSetting>) {
                smallClock.events.onReactiveAxesChanged(axes)
                largeClock.events.onReactiveAxesChanged(axes)
            }
        }

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        val theme = ThemeConfig(isDarkTheme, assets.seedColor)
        smallClock.run {
            events.onThemeChanged(theme)
            animations.doze(dozeFraction)
            animations.fold(foldFraction)
            events.onTimeTick()
        }

        largeClock.run {
            events.onThemeChanged(theme)
            animations.doze(dozeFraction)
            animations.fold(foldFraction)
            events.onTimeTick()
        }
    }

    override fun dump(pw: PrintWriter) {}
}
