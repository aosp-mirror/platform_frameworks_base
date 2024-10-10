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
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockAnimations
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockFaceEvents
import com.android.systemui.plugins.clocks.ClockFaceLayout
import com.android.systemui.plugins.clocks.ClockReactiveSetting
import com.android.systemui.plugins.clocks.ClockTickRate
import com.android.systemui.plugins.clocks.DefaultClockFaceLayout
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.shared.clocks.view.DigitalClockFaceView
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

interface ClockEventUnion : ClockEvents, ClockFaceEvents

class SimpleClockFaceController(
    ctx: Context,
    val assets: AssetLoader,
    face: ClockFace,
    isLargeClock: Boolean,
    messageBuffer: MessageBuffer,
) : ClockFaceController {
    override val view: View
    override val config: ClockFaceConfig by lazy {
        ClockFaceConfig(
            hasCustomWeatherDataDisplay = layers.any { it.config.hasCustomWeatherDataDisplay },
            hasCustomPositionUpdatedAnimation =
                layers.any { it.config.hasCustomPositionUpdatedAnimation },
            tickRate = getTickRate(),
            useCustomClockScene = layers.any { it.config.useCustomClockScene },
        )
    }

    val layers = mutableListOf<SimpleClockLayerController>()

    val timespecHandler = DigitalTimespecHandler(DigitalTimespec.TIME_FULL_FORMAT, "hh:mm")

    init {
        val lp = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        lp.gravity = Gravity.CENTER
        view =
            if (face.layers.size == 1) {
                // Optimize a clocks with a single layer by excluding the face level view group. We
                // expect the view container from the host process to always be a FrameLayout.
                val layer = face.layers[0]
                val controller =
                    SimpleClockLayerController.Factory.create(
                        ctx,
                        assets,
                        layer,
                        isLargeClock,
                        messageBuffer,
                    )
                layers.add(controller)
                controller.view.layoutParams = lp
                controller.view
            } else {
                // For multiple views, we use an intermediate RelativeLayout so that we can do some
                // intelligent laying out between the children views.
                val group = SimpleClockRelativeLayout(ctx, face.faceLayout)
                group.layoutParams = lp
                group.gravity = Gravity.CENTER
                group.clipChildren = false
                for (layer in face.layers) {
                    face.faceLayout?.let {
                        if (layer is DigitalHandLayer) {
                            layer.faceLayout = it
                        }
                    }
                    val controller =
                        SimpleClockLayerController.Factory.create(
                            ctx,
                            assets,
                            layer,
                            isLargeClock,
                            messageBuffer,
                        )
                    group.addView(controller.view)
                    layers.add(controller)
                }
                group
            }
    }

    override val layout: ClockFaceLayout =
        DefaultClockFaceLayout(view).apply {
            views[0].id =
                if (isLargeClock) {
                    assets.getResourcesId("lockscreen_clock_view_large")
                } else {
                    assets.getResourcesId("lockscreen_clock_view")
                }
        }

    override val events =
        object : ClockEventUnion {
            override var isReactiveTouchInteractionEnabled = false
                get() = field
                set(value) {
                    field = value
                    layers.forEach { it.events.isReactiveTouchInteractionEnabled = value }
                }

            override fun onTimeTick() {
                timespecHandler.updateTime()
                if (
                    config.tickRate == ClockTickRate.PER_MINUTE ||
                        view.contentDescription != timespecHandler.getContentDescription()
                ) {
                    view.contentDescription = timespecHandler.getContentDescription()
                }
                layers.forEach { it.faceEvents.onTimeTick() }
            }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                timespecHandler.timeZone = timeZone
                layers.forEach { it.events.onTimeZoneChanged(timeZone) }
            }

            override fun onTimeFormatChanged(is24Hr: Boolean) {
                timespecHandler.is24Hr = is24Hr
                layers.forEach { it.events.onTimeFormatChanged(is24Hr) }
            }

            override fun onLocaleChanged(locale: Locale) {
                timespecHandler.updateLocale(locale)
                layers.forEach { it.events.onLocaleChanged(locale) }
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                layers.forEach { it.faceEvents.onFontSettingChanged(fontSizePx) }
            }

            override fun onColorPaletteChanged(resources: Resources) {
                layers.forEach {
                    it.events.onColorPaletteChanged(resources)
                    it.updateColors()
                }
            }

            override fun onSeedColorChanged(seedColor: Int?) {
                layers.forEach {
                    it.events.onSeedColorChanged(seedColor)
                    it.updateColors()
                }
            }

            override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                layers.forEach { it.faceEvents.onRegionDarknessChanged(isRegionDark) }
            }

            override fun onReactiveAxesChanged(axes: List<ClockReactiveSetting>) {}

            /**
             * targetRegion passed to all customized clock applies counter translationY of
             * KeyguardStatusView and keyguard_large_clock_top_margin from default clock
             */
            override fun onTargetRegionChanged(targetRegion: Rect?) {
                // When a clock needs to be aligned with screen, like weather clock
                // it needs to offset back the translation of keyguard_large_clock_top_margin
                if (view is DigitalClockFaceView && view.isAlignedWithScreen()) {
                    val topMargin = getKeyguardLargeClockTopMargin(assets)
                    targetRegion?.let {
                        val (_, yDiff) = computeLayoutDiff(view, it, isLargeClock)
                        // In LS, we use yDiff to counter translate
                        // the translation of KeyguardLargeClockTopMargin
                        // With the targetRegion passed from picker,
                        // we will have yDiff = 0, no translation is needed for weather clock
                        if (yDiff.toInt() != 0) view.translationY = yDiff - topMargin / 2
                    }
                    return
                }

                var maxWidth = 0f
                var maxHeight = 0f

                for (layer in layers) {
                    layer.faceEvents.onTargetRegionChanged(targetRegion)
                    maxWidth = max(maxWidth, layer.view.layoutParams.width.toFloat())
                    maxHeight = max(maxHeight, layer.view.layoutParams.height.toFloat())
                }

                val lp =
                    if (maxHeight <= 0 || maxWidth <= 0 || targetRegion == null) {
                        // No specified width/height. Just match parent size.
                        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    } else {
                        // Scale to fit in targetRegion based on largest child elements.
                        val ratio = maxWidth / maxHeight
                        val targetRatio = targetRegion.width() / targetRegion.height().toFloat()
                        val scale =
                            if (ratio > targetRatio) targetRegion.width() / maxWidth
                            else targetRegion.height() / maxHeight

                        FrameLayout.LayoutParams(
                            (maxWidth * scale).toInt(),
                            (maxHeight * scale).toInt(),
                        )
                    }

                lp.gravity = Gravity.CENTER
                view.layoutParams = lp
                targetRegion?.let {
                    val (xDiff, yDiff) = computeLayoutDiff(view, it, isLargeClock)
                    view.translationX = xDiff
                    view.translationY = yDiff
                }
            }

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}

            override fun onWeatherDataChanged(data: WeatherData) {
                layers.forEach { it.events.onWeatherDataChanged(data) }
            }

            override fun onAlarmDataChanged(data: AlarmData) {
                layers.forEach { it.events.onAlarmDataChanged(data) }
            }

            override fun onZenDataChanged(data: ZenData) {
                layers.forEach { it.events.onZenDataChanged(data) }
            }
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() {
                layers.forEach { it.animations.enter() }
            }

            override fun doze(fraction: Float) {
                layers.forEach { it.animations.doze(fraction) }
            }

            override fun fold(fraction: Float) {
                layers.forEach { it.animations.fold(fraction) }
            }

            override fun charge() {
                layers.forEach { it.animations.charge() }
            }

            override fun onPickerCarouselSwiping(swipingFraction: Float) {
                face.pickerScale?.let {
                    view.scaleX = swipingFraction * (1 - it.scaleX) + it.scaleX
                    view.scaleY = swipingFraction * (1 - it.scaleY) + it.scaleY
                }
                if (!(view is DigitalClockFaceView && view.isAlignedWithScreen())) {
                    val topMargin = getKeyguardLargeClockTopMargin(assets)
                    view.translationY = topMargin / 2F * swipingFraction
                }
                layers.forEach { it.animations.onPickerCarouselSwiping(swipingFraction) }
                view.invalidate()
            }

            override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {
                layers.forEach { it.animations.onPositionUpdated(fromLeft, direction, fraction) }
            }

            override fun onPositionUpdated(distance: Float, fraction: Float) {
                layers.forEach { it.animations.onPositionUpdated(distance, fraction) }
            }
        }

    private fun getTickRate(): ClockTickRate {
        var tickRate = ClockTickRate.PER_MINUTE
        for (layer in layers) {
            if (layer.config.tickRate.value < tickRate.value) {
                tickRate = layer.config.tickRate
            }
        }
        return tickRate
    }

    private fun getKeyguardLargeClockTopMargin(assets: AssetLoader): Int {
        val topMarginRes =
            assets.resolveResourceId(null, "dimen", "keyguard_large_clock_top_margin")
        if (topMarginRes != null) {
            val (res, id) = topMarginRes
            return res.getDimensionPixelSize(id)
        }
        return 0
    }
}
