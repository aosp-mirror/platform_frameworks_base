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

import android.graphics.Rect
import androidx.annotation.VisibleForTesting
import com.android.app.animation.Interpolators
import com.android.systemui.log.core.Logger
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockAnimations
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceEvents
import com.android.systemui.plugins.clocks.ClockFontAxisSetting
import com.android.systemui.plugins.clocks.ThemeConfig
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.shared.clocks.view.FlexClockView
import com.android.systemui.shared.clocks.view.HorizontalAlignment
import com.android.systemui.shared.clocks.view.VerticalAlignment
import java.util.Locale
import java.util.TimeZone

class ComposedDigitalLayerController(private val clockCtx: ClockContext) :
    SimpleClockLayerController {
    private val logger =
        Logger(clockCtx.messageBuffer, ComposedDigitalLayerController::class.simpleName!!)

    val layerControllers = mutableListOf<SimpleClockLayerController>()
    val dozeState = DefaultClockController.AnimationState(1F)

    override val view = FlexClockView(clockCtx)

    init {
        fun createController(cfg: LayerConfig) {
            val controller = SimpleDigitalHandLayerController(clockCtx, cfg, isLargeClock = true)
            view.addView(controller.view)
            layerControllers.add(controller)
        }

        val layerCfg =
            LayerConfig(
                style = FontTextStyle(lineHeight = 147.25f),
                timespec = DigitalTimespec.DIGIT_PAIR,
                alignment = DigitalAlignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER),
                aodStyle =
                    FontTextStyle(
                        transitionInterpolator = Interpolators.EMPHASIZED,
                        transitionDuration = 750,
                    ),

                // Placeholder
                dateTimeFormat = "hh:mm",
            )

        createController(layerCfg.copy(dateTimeFormat = "hh"))
        createController(layerCfg.copy(dateTimeFormat = "mm"))
    }

    private fun refreshTime() {
        layerControllers.forEach { it.faceEvents.onTimeTick() }
        view.refreshTime()
    }

    override val events =
        object : ClockEvents {
            override fun onTimeZoneChanged(timeZone: TimeZone) {
                layerControllers.forEach { it.events.onTimeZoneChanged(timeZone) }
                refreshTime()
            }

            override fun onTimeFormatChanged(is24Hr: Boolean) {
                layerControllers.forEach { it.events.onTimeFormatChanged(is24Hr) }
                refreshTime()
            }

            override fun onLocaleChanged(locale: Locale) {
                layerControllers.forEach { it.events.onLocaleChanged(locale) }
                view.onLocaleChanged(locale)
                refreshTime()
            }

            override fun onWeatherDataChanged(data: WeatherData) {}

            override fun onAlarmDataChanged(data: AlarmData) {}

            override fun onZenDataChanged(data: ZenData) {}

            override fun onFontAxesChanged(axes: List<ClockFontAxisSetting>) {
                view.updateAxes(axes)
            }

            override var isReactiveTouchInteractionEnabled
                get() = view.isReactiveTouchInteractionEnabled
                set(value) {
                    view.isReactiveTouchInteractionEnabled = value
                }
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() {
                refreshTime()
            }

            override fun doze(fraction: Float) {
                val (hasChanged, hasJumped) = dozeState.update(fraction)
                if (hasChanged) view.animateDoze(dozeState.isActive, !hasJumped)
                view.dozeFraction = fraction
                view.invalidate()
            }

            override fun fold(fraction: Float) {
                refreshTime()
            }

            override fun charge() {
                view.animateCharge()
            }

            override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}

            override fun onPositionUpdated(distance: Float, fraction: Float) {}

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onFidgetTap(x: Float, y: Float) {
                view.animateFidget(x, y)
            }
        }

    override val faceEvents =
        object : ClockFaceEvents {
            override fun onTimeTick() {
                refreshTime()
            }

            override fun onThemeChanged(theme: ThemeConfig) {
                val color =
                    when {
                        theme.seedColor != null -> theme.seedColor!!
                        theme.isDarkTheme ->
                            clockCtx.resources.getColor(android.R.color.system_accent1_100)
                        else -> clockCtx.resources.getColor(android.R.color.system_accent2_600)
                    }

                view.updateColor(color)
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                view.onFontSettingChanged(fontSizePx)
            }

            override fun onTargetRegionChanged(targetRegion: Rect?) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }

    override val config =
        ClockFaceConfig(
            hasCustomWeatherDataDisplay = false,
            hasCustomPositionUpdatedAnimation = true,
        )

    @VisibleForTesting
    override var fakeTimeMills: Long? = null
        get() = field
        set(timeInMills) {
            field = timeInMills
            for (layerController in layerControllers) {
                layerController.fakeTimeMills = timeInMills
            }
        }
}
