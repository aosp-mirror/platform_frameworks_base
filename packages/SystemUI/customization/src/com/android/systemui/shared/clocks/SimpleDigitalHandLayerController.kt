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
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.RelativeLayout
import androidx.annotation.VisibleForTesting
import com.android.systemui.customization.R
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
import com.android.systemui.shared.clocks.view.HorizontalAlignment
import com.android.systemui.shared.clocks.view.SimpleDigitalClockTextView
import com.android.systemui.shared.clocks.view.VerticalAlignment
import java.util.Locale
import java.util.TimeZone

private val TAG = SimpleDigitalHandLayerController::class.simpleName!!

// TODO(b/364680879): The remains of ClockDesign. Cut further.
data class LayerConfig(
    val style: FontTextStyle,
    val aodStyle: FontTextStyle,
    val alignment: DigitalAlignment,
    val timespec: DigitalTimespec,
    val dateTimeFormat: String,
) {
    fun generateDigitalLayerIdString(): String {
        return when {
            timespec == DigitalTimespec.TIME_FULL_FORMAT -> "$timespec"
            "h" in dateTimeFormat -> "HOUR_$timespec"
            else -> "MINUTE_$timespec"
        }
    }
}

data class DigitalAlignment(
    val horizontalAlignment: HorizontalAlignment?,
    val verticalAlignment: VerticalAlignment?,
)

data class FontTextStyle(
    val lineHeight: Float? = null,
    val fontSizeScale: Float? = null,
    val transitionDuration: Long = -1L,
    val transitionInterpolator: Interpolator? = null,
)

enum class DigitalTimespec {
    TIME_FULL_FORMAT,
    DIGIT_PAIR,
    FIRST_DIGIT,
    SECOND_DIGIT,
}

open class SimpleDigitalHandLayerController(
    private val clockCtx: ClockContext,
    private val layerCfg: LayerConfig,
    isLargeClock: Boolean,
) : SimpleClockLayerController {
    override val view = SimpleDigitalClockTextView(clockCtx, isLargeClock)
    private val logger = Logger(clockCtx.messageBuffer, TAG)
    val timespec = DigitalTimespecHandler(layerCfg.timespec, layerCfg.dateTimeFormat)

    @VisibleForTesting
    override var fakeTimeMills: Long?
        get() = timespec.fakeTimeMills
        set(value) {
            timespec.fakeTimeMills = value
        }

    override val config = ClockFaceConfig()
    var dozeState: DefaultClockController.AnimationState? = null

    init {
        view.layoutParams =
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        layerCfg.alignment.verticalAlignment?.let { view.verticalAlignment = it }
        layerCfg.alignment.horizontalAlignment?.let { view.horizontalAlignment = it }
        view.applyStyles(layerCfg.style, layerCfg.aodStyle)
        view.id =
            clockCtx.resources.getIdentifier(
                layerCfg.generateDigitalLayerIdString(),
                "id",
                clockCtx.context.getPackageName(),
            )
    }

    fun refreshTime() {
        timespec.updateTime()
        val text = timespec.getDigitString()
        if (view.text != text) {
            view.text = text
            view.refreshTime()
            logger.d({ "refreshTime: new text=$str1" }) { str1 = text }
        }
    }

    private fun applyLayout() {
        // TODO: Remove NO-OP
        if (view.layoutParams is RelativeLayout.LayoutParams) {
            val lp = view.layoutParams as RelativeLayout.LayoutParams
            lp.addRule(RelativeLayout.TEXT_ALIGNMENT_CENTER)
            when (view.id) {
                R.id.HOUR_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_VERTICAL)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                }
                R.id.MINUTE_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_VERTICAL)
                    lp.addRule(RelativeLayout.END_OF, R.id.HOUR_DIGIT_PAIR)
                }
                else -> {
                    throw Exception("cannot apply two pairs layout to view ${view.id}")
                }
            }
            view.layoutParams = lp
        }
    }

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false

            override fun onLocaleChanged(locale: Locale) {
                timespec.updateLocale(locale)
                refreshTime()
            }

            /** Call whenever the text time format changes (12hr vs 24hr) */
            override fun onTimeFormatChanged(is24Hr: Boolean) {
                timespec.is24Hr = is24Hr
                refreshTime()
            }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                timespec.timeZone = timeZone
                refreshTime()
            }

            override fun onWeatherDataChanged(data: WeatherData) {}

            override fun onAlarmDataChanged(data: AlarmData) {}

            override fun onZenDataChanged(data: ZenData) {}

            override fun onFontAxesChanged(axes: List<ClockFontAxisSetting>) {
                view.updateAxes(axes)
            }
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() {
                applyLayout()
                refreshTime()
            }

            override fun doze(fraction: Float) {
                if (dozeState == null) {
                    dozeState = DefaultClockController.AnimationState(fraction)
                    view.animateDoze(dozeState!!.isActive, false)
                } else {
                    val (hasChanged, hasJumped) = dozeState!!.update(fraction)
                    if (hasChanged) view.animateDoze(dozeState!!.isActive, !hasJumped)
                }
                view.dozeFraction = fraction
            }

            override fun fold(fraction: Float) {
                applyLayout()
                refreshTime()
            }

            override fun charge() {
                view.animateCharge()
            }

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}

            override fun onPositionUpdated(distance: Float, fraction: Float) {}

            override fun onFidgetTap(x: Float, y: Float) {
                view.animateFidget(x, y)
            }
        }

    override val faceEvents =
        object : ClockFaceEvents {
            override fun onTimeTick() {
                refreshTime()
                if (layerCfg.timespec == DigitalTimespec.TIME_FULL_FORMAT) {
                    view.contentDescription = timespec.getContentDescription()
                }
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                view.applyTextSize(fontSizePx)
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
                refreshTime()
            }

            override fun onTargetRegionChanged(targetRegion: Rect?) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }
}
