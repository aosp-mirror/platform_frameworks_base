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
import android.view.View
import android.view.ViewGroup
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
import com.android.systemui.shared.clocks.view.SimpleDigitalClockView
import java.util.Locale
import java.util.TimeZone

private val TAG = SimpleDigitalHandLayerController::class.simpleName!!

open class SimpleDigitalHandLayerController<T>(
    private val clockCtx: ClockContext,
    private val layer: DigitalHandLayer,
    override val view: T,
) : SimpleClockLayerController where T : View, T : SimpleDigitalClockView {
    private val logger = Logger(clockCtx.messageBuffer, TAG)
    val timespec = DigitalTimespecHandler(layer.timespec, layer.dateTimeFormat)

    @VisibleForTesting
    fun hasLeadingZero() = layer.dateTimeFormat.startsWith("hh") || timespec.is24Hr

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
        if (layer.alignment != null) {
            layer.alignment.verticalAlignment?.let { view.verticalAlignment = it }
            layer.alignment.horizontalAlignment?.let { view.horizontalAlignment = it }
        }
        view.applyStyles(layer.style, layer.aodStyle)
        view.id =
            clockCtx.resources.getIdentifier(
                generateDigitalLayerIdString(layer),
                "id",
                clockCtx.context.getPackageName(),
            )
    }

    fun applyLayout(layout: DigitalFaceLayout?) {
        when (layout) {
            DigitalFaceLayout.FOUR_DIGITS_ALIGN_CENTER,
            DigitalFaceLayout.FOUR_DIGITS_HORIZONTAL -> applyFourDigitsLayout(layout)
            DigitalFaceLayout.TWO_PAIRS_HORIZONTAL,
            DigitalFaceLayout.TWO_PAIRS_VERTICAL -> applyTwoPairsLayout(layout)
            else -> {
                // one view always use FrameLayout
                // no need to change here
            }
        }
        applyMargin()
    }

    private fun applyMargin() {
        if (view.layoutParams is RelativeLayout.LayoutParams) {
            val lp = view.layoutParams as RelativeLayout.LayoutParams
            layer.marginRatio?.let {
                lp.setMargins(
                    /* left = */ (it.left * view.measuredWidth).toInt(),
                    /* top = */ (it.top * view.measuredHeight).toInt(),
                    /* right = */ (it.right * view.measuredWidth).toInt(),
                    /* bottom = */ (it.bottom * view.measuredHeight).toInt(),
                )
            }
            view.layoutParams = lp
        }
    }

    private fun applyTwoPairsLayout(twoPairsLayout: DigitalFaceLayout) {
        val lp = view.layoutParams as RelativeLayout.LayoutParams
        lp.addRule(RelativeLayout.TEXT_ALIGNMENT_CENTER)
        if (twoPairsLayout == DigitalFaceLayout.TWO_PAIRS_HORIZONTAL) {
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
        } else {
            when (view.id) {
                R.id.HOUR_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                }
                R.id.MINUTE_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                    lp.addRule(RelativeLayout.BELOW, R.id.HOUR_DIGIT_PAIR)
                }
                else -> {
                    throw Exception("cannot apply two pairs layout to view ${view.id}")
                }
            }
        }
        view.layoutParams = lp
    }

    private fun applyFourDigitsLayout(fourDigitsfaceLayout: DigitalFaceLayout) {
        val lp = view.layoutParams as RelativeLayout.LayoutParams
        when (fourDigitsfaceLayout) {
            DigitalFaceLayout.FOUR_DIGITS_ALIGN_CENTER -> {
                when (view.id) {
                    R.id.HOUR_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    }
                    R.id.HOUR_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.END_OF, R.id.HOUR_FIRST_DIGIT)
                        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.HOUR_FIRST_DIGIT)
                    }
                    R.id.MINUTE_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.ALIGN_START, R.id.HOUR_FIRST_DIGIT)
                        lp.addRule(RelativeLayout.BELOW, R.id.HOUR_FIRST_DIGIT)
                    }
                    R.id.MINUTE_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.ALIGN_START, R.id.HOUR_SECOND_DIGIT)
                        lp.addRule(RelativeLayout.BELOW, R.id.HOUR_SECOND_DIGIT)
                    }
                    else -> {
                        throw Exception("cannot apply four digits layout to view ${view.id}")
                    }
                }
            }
            DigitalFaceLayout.FOUR_DIGITS_HORIZONTAL -> {
                when (view.id) {
                    R.id.HOUR_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                    }
                    R.id.HOUR_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.END_OF, R.id.HOUR_FIRST_DIGIT)
                    }
                    R.id.MINUTE_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.END_OF, R.id.HOUR_SECOND_DIGIT)
                    }
                    R.id.MINUTE_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.END_OF, R.id.MINUTE_FIRST_DIGIT)
                    }
                    else -> {
                        throw Exception("cannot apply FOUR_DIGITS_HORIZONTAL to view ${view.id}")
                    }
                }
            }
            else -> {
                throw IllegalArgumentException(
                    "applyFourDigitsLayout function should not " +
                        "have parameters as ${layer.faceLayout}"
                )
            }
        }
        if (lp == view.layoutParams) {
            return
        }
        view.layoutParams = lp
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
                applyLayout(layer.faceLayout)
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
                applyLayout(layer.faceLayout)
                refreshTime()
            }

            override fun charge() {
                view.animateCharge()
            }

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}

            override fun onPositionUpdated(distance: Float, fraction: Float) {}
        }

    override val faceEvents =
        object : ClockFaceEvents {
            override fun onTimeTick() {
                refreshTime()
                if (
                    layer.timespec == DigitalTimespec.TIME_FULL_FORMAT ||
                        layer.timespec == DigitalTimespec.DATE_FORMAT
                ) {
                    view.contentDescription = timespec.getContentDescription()
                }
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                view.applyTextSize(fontSizePx)
                applyMargin()
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
