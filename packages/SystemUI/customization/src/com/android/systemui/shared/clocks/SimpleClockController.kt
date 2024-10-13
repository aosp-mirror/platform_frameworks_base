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
import com.android.systemui.monet.Style as MonetStyle
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockReactiveSetting
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone

/** Controller for a simple json specified clock */
class SimpleClockController(
    private val ctx: Context,
    private val assets: AssetLoader,
    val design: ClockDesign,
    val messageBuffers: ClockMessageBuffers?,
) : ClockController {
    override val smallClock = run {
        val buffer = messageBuffers?.smallClockMessageBuffer ?: LogUtil.DEFAULT_MESSAGE_BUFFER
        SimpleClockFaceController(
            ctx,
            assets.copy(messageBuffer = buffer),
            design.small ?: design.large!!,
            false,
            buffer,
        )
    }

    override val largeClock = run {
        val buffer = messageBuffers?.largeClockMessageBuffer ?: LogUtil.DEFAULT_MESSAGE_BUFFER
        SimpleClockFaceController(
            ctx,
            assets.copy(messageBuffer = buffer),
            design.large ?: design.small!!,
            true,
            buffer,
        )
    }

    override val config: ClockConfig by lazy {
        ClockConfig(
            design.id,
            design.name?.let { assets.tryReadString(it) ?: it } ?: "",
            design.description?.let { assets.tryReadString(it) ?: it } ?: "",
            isReactiveToTone =
                design.colorPalette == null || design.colorPalette == MonetStyle.CLOCK,
            useAlternateSmartspaceAODTransition =
                smallClock.config.hasCustomWeatherDataDisplay ||
                    largeClock.config.hasCustomWeatherDataDisplay,
            useCustomClockScene =
                smallClock.config.useCustomClockScene || largeClock.config.useCustomClockScene,
        )
    }

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false
                set(value) {
                    field = value
                    smallClock.events.isReactiveTouchInteractionEnabled = value
                    largeClock.events.isReactiveTouchInteractionEnabled = value
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

            override fun onColorPaletteChanged(resources: Resources) {
                assets.refreshColorPalette(design.colorPalette)
                smallClock.assets.refreshColorPalette(design.colorPalette)
                largeClock.assets.refreshColorPalette(design.colorPalette)

                smallClock.events.onColorPaletteChanged(resources)
                largeClock.events.onColorPaletteChanged(resources)
            }

            override fun onSeedColorChanged(seedColor: Int?) {
                assets.setSeedColor(seedColor, design.colorPalette)
                smallClock.assets.setSeedColor(seedColor, design.colorPalette)
                largeClock.assets.setSeedColor(seedColor, design.colorPalette)

                smallClock.events.onSeedColorChanged(seedColor)
                largeClock.events.onSeedColorChanged(seedColor)
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

    override fun initialize(resources: Resources, dozeFraction: Float, foldFraction: Float) {
        events.onColorPaletteChanged(resources)
        smallClock.animations.doze(dozeFraction)
        largeClock.animations.doze(dozeFraction)
        smallClock.animations.fold(foldFraction)
        largeClock.animations.fold(foldFraction)
        smallClock.events.onTimeTick()
        largeClock.events.onTimeTick()
    }

    override fun dump(pw: PrintWriter) {}
}
