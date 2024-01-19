/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.icu.text.NumberFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import com.android.systemui.customization.R
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockAnimations
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockFaceEvents
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockSettings
import com.android.systemui.plugins.clocks.DefaultClockFaceLayout
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone

/**
 * Controls the default clock visuals.
 *
 * This serves as an adapter between the clock interface and the AnimatableClockView used by the
 * existing lockscreen clock.
 */
class DefaultClockController(
    ctx: Context,
    private val layoutInflater: LayoutInflater,
    private val resources: Resources,
    private val settings: ClockSettings?,
    private val hasStepClockAnimation: Boolean = false,
    private val migratedClocks: Boolean = false,
    messageBuffers: ClockMessageBuffers? = null,
) : ClockController {
    override val smallClock: DefaultClockFaceController
    override val largeClock: LargeClockFaceController
    private val clocks: List<AnimatableClockView>

    private val burmeseNf = NumberFormat.getInstance(Locale.forLanguageTag("my"))
    private val burmeseNumerals = burmeseNf.format(FORMAT_NUMBER.toLong())
    private val burmeseLineSpacing =
        resources.getFloat(R.dimen.keyguard_clock_line_spacing_scale_burmese)
    private val defaultLineSpacing = resources.getFloat(R.dimen.keyguard_clock_line_spacing_scale)
    protected var onSecondaryDisplay: Boolean = false

    override val events: DefaultClockEvents
    override val config: ClockConfig by lazy {
        ClockConfig(
            DEFAULT_CLOCK_ID,
            resources.getString(R.string.clock_default_name),
            resources.getString(R.string.clock_default_description)
        )
    }

    init {
        val parent = FrameLayout(ctx)
        smallClock =
            DefaultClockFaceController(
                layoutInflater.inflate(R.layout.clock_default_small, parent, false)
                    as AnimatableClockView,
                settings?.seedColor,
                messageBuffers?.smallClockMessageBuffer
            )
        largeClock =
            LargeClockFaceController(
                layoutInflater.inflate(R.layout.clock_default_large, parent, false)
                    as AnimatableClockView,
                settings?.seedColor,
                messageBuffers?.largeClockMessageBuffer
            )
        clocks = listOf(smallClock.view, largeClock.view)

        events = DefaultClockEvents()
        events.onLocaleChanged(Locale.getDefault())
    }

    override fun initialize(resources: Resources, dozeFraction: Float, foldFraction: Float) {
        largeClock.recomputePadding(null)
        largeClock.animations = LargeClockAnimations(largeClock.view, dozeFraction, foldFraction)
        smallClock.animations = DefaultClockAnimations(smallClock.view, dozeFraction, foldFraction)
        events.onColorPaletteChanged(resources)
        events.onTimeZoneChanged(TimeZone.getDefault())
        smallClock.events.onTimeTick()
        largeClock.events.onTimeTick()
    }

    open inner class DefaultClockFaceController(
        override val view: AnimatableClockView,
        var seedColor: Int?,
        messageBuffer: MessageBuffer?,
    ) : ClockFaceController {

        // MAGENTA is a placeholder, and will be assigned correctly in initialize
        private var currentColor = Color.MAGENTA
        private var isRegionDark = false
        protected var targetRegion: Rect? = null

        override val config = ClockFaceConfig()
        override val layout = DefaultClockFaceLayout(view)

        override var animations: DefaultClockAnimations = DefaultClockAnimations(view, 0f, 0f)
            internal set

        init {
            if (seedColor != null) {
                currentColor = seedColor!!
            }
            view.setColors(DOZE_COLOR, currentColor)
            messageBuffer?.let { view.messageBuffer = it }
        }

        override val events =
            object : ClockFaceEvents {
                override fun onTimeTick() = view.refreshTime()

                override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                    this@DefaultClockFaceController.isRegionDark = isRegionDark
                    updateColor()
                }

                override fun onTargetRegionChanged(targetRegion: Rect?) {
                    this@DefaultClockFaceController.targetRegion = targetRegion
                    recomputePadding(targetRegion)
                }

                override fun onFontSettingChanged(fontSizePx: Float) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                    recomputePadding(targetRegion)
                }

                override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {
                    this@DefaultClockController.onSecondaryDisplay = onSecondaryDisplay
                    recomputePadding(null)
                }
            }

        open fun recomputePadding(targetRegion: Rect?) {}

        fun updateColor() {
            val color =
                if (seedColor != null) {
                    seedColor!!
                } else if (isRegionDark) {
                    resources.getColor(android.R.color.system_accent1_100)
                } else {
                    resources.getColor(android.R.color.system_accent2_600)
                }

            if (currentColor == color) {
                return
            }

            currentColor = color
            view.setColors(DOZE_COLOR, color)
            if (!animations.dozeState.isActive) {
                view.animateColorChange()
            }
        }
    }

    inner class LargeClockFaceController(
        view: AnimatableClockView,
        seedColor: Int?,
        messageBuffer: MessageBuffer?,
    ) : DefaultClockFaceController(view, seedColor, messageBuffer) {
        override val layout = DefaultClockFaceLayout(view)
        override val config =
            ClockFaceConfig(hasCustomPositionUpdatedAnimation = hasStepClockAnimation)

        init {
            view.migratedClocks = migratedClocks
            view.hasCustomPositionUpdatedAnimation = hasStepClockAnimation
            animations = LargeClockAnimations(view, 0f, 0f)
        }

        override fun recomputePadding(targetRegion: Rect?) {
            if (migratedClocks) {
                return
            }
            // We center the view within the targetRegion instead of within the parent
            // view by computing the difference and adding that to the padding.
            val lp = view.getLayoutParams() as FrameLayout.LayoutParams
            lp.topMargin =
                if (onSecondaryDisplay) {
                    // On the secondary display we don't want any additional top/bottom margin.
                    0
                } else {
                    val parent = view.parent
                    val yDiff =
                        if (targetRegion != null && parent is View && parent.isLaidOut())
                            targetRegion.centerY() - parent.height / 2f
                        else 0f
                    (-0.5f * view.bottom + yDiff).toInt()
                }
            view.setLayoutParams(lp)
        }

        /** See documentation at [AnimatableClockView.offsetGlyphsForStepClockAnimation]. */
        fun offsetGlyphsForStepClockAnimation(fromLeft: Int, direction: Int, fraction: Float) {
            view.offsetGlyphsForStepClockAnimation(fromLeft, direction, fraction)
        }
    }

    inner class DefaultClockEvents : ClockEvents {
        override fun onTimeFormatChanged(is24Hr: Boolean) =
            clocks.forEach { it.refreshFormat(is24Hr) }

        override fun onTimeZoneChanged(timeZone: TimeZone) =
            clocks.forEach { it.onTimeZoneChanged(timeZone) }

        override fun onColorPaletteChanged(resources: Resources) {
            largeClock.updateColor()
            smallClock.updateColor()
        }

        override fun onSeedColorChanged(seedColor: Int?) {
            largeClock.seedColor = seedColor
            smallClock.seedColor = seedColor

            largeClock.updateColor()
            smallClock.updateColor()
        }

        override fun onLocaleChanged(locale: Locale) {
            val nf = NumberFormat.getInstance(locale)
            if (nf.format(FORMAT_NUMBER.toLong()) == burmeseNumerals) {
                clocks.forEach { it.setLineSpacingScale(burmeseLineSpacing) }
            } else {
                clocks.forEach { it.setLineSpacingScale(defaultLineSpacing) }
            }

            clocks.forEach { it.refreshFormat() }
        }

        override fun onWeatherDataChanged(data: WeatherData) {}
        override fun onAlarmDataChanged(data: AlarmData) {}
        override fun onZenDataChanged(data: ZenData) {}
    }

    open inner class DefaultClockAnimations(
        val view: AnimatableClockView,
        dozeFraction: Float,
        foldFraction: Float,
    ) : ClockAnimations {
        internal val dozeState = AnimationState(dozeFraction)
        private val foldState = AnimationState(foldFraction)

        init {
            if (foldState.isActive) {
                view.animateFoldAppear(false)
            } else {
                view.animateDoze(dozeState.isActive, false)
            }
        }

        override fun enter() {
            if (!dozeState.isActive) {
                view.animateAppearOnLockscreen()
            }
        }

        override fun charge() = view.animateCharge { dozeState.isActive }

        override fun fold(fraction: Float) {
            val (hasChanged, hasJumped) = foldState.update(fraction)
            if (hasChanged) {
                view.animateFoldAppear(!hasJumped)
            }
        }

        override fun doze(fraction: Float) {
            val (hasChanged, hasJumped) = dozeState.update(fraction)
            if (hasChanged) {
                view.animateDoze(dozeState.isActive, !hasJumped)
            }
        }

        override fun onPickerCarouselSwiping(swipingFraction: Float) {
            // TODO(b/278936436): refactor this part when we change recomputePadding
            // when on the side, swipingFraction = 0, translationY should offset
            // the top margin change in recomputePadding to make clock be centered
            view.translationY = 0.5f * view.bottom * (1 - swipingFraction)
        }

        override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}
    }

    inner class LargeClockAnimations(
        view: AnimatableClockView,
        dozeFraction: Float,
        foldFraction: Float,
    ) : DefaultClockAnimations(view, dozeFraction, foldFraction) {
        override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {
            largeClock.offsetGlyphsForStepClockAnimation(fromLeft, direction, fraction)
        }
    }

    class AnimationState(
        var fraction: Float,
    ) {
        var isActive: Boolean = fraction > 0.5f
        fun update(newFraction: Float): Pair<Boolean, Boolean> {
            if (newFraction == fraction) {
                return Pair(isActive, false)
            }
            val wasActive = isActive
            val hasJumped =
                (fraction == 0f && newFraction == 1f) || (fraction == 1f && newFraction == 0f)
            isActive = newFraction > fraction
            fraction = newFraction
            return Pair(wasActive != isActive, hasJumped)
        }
    }

    override fun dump(pw: PrintWriter) {
        pw.print("smallClock=")
        smallClock.view.dump(pw)

        pw.print("largeClock=")
        largeClock.view.dump(pw)
    }

    companion object {
        @VisibleForTesting const val DOZE_COLOR = Color.WHITE
        private const val FORMAT_NUMBER = 1234567890
    }
}
