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
import android.graphics.drawable.Drawable
import android.icu.text.NumberFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.ClockAnimations
import com.android.systemui.plugins.ClockEvents
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.shared.R
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

private val TAG = DefaultClockProvider::class.simpleName
const val DEFAULT_CLOCK_NAME = "Default Clock"
const val DEFAULT_CLOCK_ID = "DEFAULT"

/** Provides the default system clock */
class DefaultClockProvider @Inject constructor(
    val ctx: Context,
    val layoutInflater: LayoutInflater,
    @Main val resources: Resources
) : ClockProvider {
    override fun getClocks(): List<ClockMetadata> =
        listOf(ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME))

    override fun createClock(id: ClockId): Clock {
        if (id != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("$id is unsupported by $TAG")
        }
        return DefaultClock(ctx, layoutInflater, resources)
    }

    override fun getClockThumbnail(id: ClockId): Drawable? {
        if (id != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("$id is unsupported by $TAG")
        }

        // TODO: Update placeholder to actual resource
        return resources.getDrawable(R.drawable.clock_default_thumbnail, null)
    }
}

/**
 * Controls the default clock visuals.
 *
 * This serves as an adapter between the clock interface and the
 * AnimatableClockView used by the existing lockscreen clock.
 */
class DefaultClock(
        ctx: Context,
        private val layoutInflater: LayoutInflater,
        private val resources: Resources
) : Clock {
    override val smallClock: AnimatableClockView
    override val largeClock: AnimatableClockView
    private val clocks get() = listOf(smallClock, largeClock)

    private val burmeseNf = NumberFormat.getInstance(Locale.forLanguageTag("my"))
    private val burmeseNumerals = burmeseNf.format(FORMAT_NUMBER.toLong())
    private val burmeseLineSpacing =
        resources.getFloat(R.dimen.keyguard_clock_line_spacing_scale_burmese)
    private val defaultLineSpacing = resources.getFloat(R.dimen.keyguard_clock_line_spacing_scale)

    override val events: ClockEvents
    override lateinit var animations: ClockAnimations
        private set

    private var smallRegionDarkness = false
    private var largeRegionDarkness = false

    init {
        val parent = FrameLayout(ctx)

        smallClock = layoutInflater.inflate(
            R.layout.clock_default_small,
            parent,
            false
        ) as AnimatableClockView

        largeClock = layoutInflater.inflate(
            R.layout.clock_default_large,
            parent,
            false
        ) as AnimatableClockView

        events = DefaultClockEvents()
        animations = DefaultClockAnimations(0f, 0f)

        events.onLocaleChanged(Locale.getDefault())

        // DOZE_COLOR is a placeholder, and will be assigned correctly in initialize
        clocks.forEach { it.setColors(DOZE_COLOR, DOZE_COLOR) }
    }

    override fun initialize(resources: Resources, dozeFraction: Float, foldFraction: Float) {
        recomputePadding()
        animations = DefaultClockAnimations(dozeFraction, foldFraction)
        events.onColorPaletteChanged(resources, true, true)
        events.onTimeZoneChanged(TimeZone.getDefault())
        events.onTimeTick()
    }

    inner class DefaultClockEvents() : ClockEvents {
        override fun onTimeTick() = clocks.forEach { it.refreshTime() }

        override fun onTimeFormatChanged(is24Hr: Boolean) =
            clocks.forEach { it.refreshFormat(is24Hr) }

        override fun onTimeZoneChanged(timeZone: TimeZone) =
            clocks.forEach { it.onTimeZoneChanged(timeZone) }

        override fun onFontSettingChanged() {
            smallClock.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimensionPixelSize(R.dimen.small_clock_text_size).toFloat()
            )
            largeClock.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimensionPixelSize(R.dimen.large_clock_text_size).toFloat()
            )
            recomputePadding()
        }

        override fun onColorPaletteChanged(
                resources: Resources,
                smallClockIsDark: Boolean,
                largeClockIsDark: Boolean
        ) {
            if (smallRegionDarkness != smallClockIsDark) {
                smallRegionDarkness = smallClockIsDark
                updateClockColor(smallClock, smallClockIsDark)
            }
            if (largeRegionDarkness != largeClockIsDark) {
                largeRegionDarkness = largeClockIsDark
                updateClockColor(largeClock, largeClockIsDark)
            }
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
    }

    inner class DefaultClockAnimations(
        dozeFraction: Float,
        foldFraction: Float
    ) : ClockAnimations {
        private var foldState = AnimationState(0f)
        private var dozeState = AnimationState(0f)

        init {
            dozeState = AnimationState(dozeFraction)
            foldState = AnimationState(foldFraction)

            if (foldState.isActive) {
                clocks.forEach { it.animateFoldAppear(false) }
            } else {
                clocks.forEach { it.animateDoze(dozeState.isActive, false) }
            }
        }

        override fun enter() {
            if (!dozeState.isActive) {
                clocks.forEach { it.animateAppearOnLockscreen() }
            }
        }

        override fun charge() = clocks.forEach { it.animateCharge { dozeState.isActive } }

        override fun fold(fraction: Float) {
            val (hasChanged, hasJumped) = foldState.update(fraction)
            if (hasChanged) {
                clocks.forEach { it.animateFoldAppear(!hasJumped) }
            }
        }

        override fun doze(fraction: Float) {
            val (hasChanged, hasJumped) = dozeState.update(fraction)
            if (hasChanged) {
                clocks.forEach { it.animateDoze(dozeState.isActive, !hasJumped) }
            }
        }
    }

    private class AnimationState(
        var fraction: Float
    ) {
        var isActive: Boolean = fraction < 0.5f
        fun update(newFraction: Float): Pair<Boolean, Boolean> {
            val wasActive = isActive
            val hasJumped = (fraction == 0f && newFraction == 1f) ||
                (fraction == 1f && newFraction == 0f)
            isActive = newFraction > fraction
            fraction = newFraction
            return Pair(wasActive != isActive, hasJumped)
        }
    }

    private fun updateClockColor(clock: AnimatableClockView, isRegionDark: Boolean) {
        val color = if (isRegionDark) {
            resources.getColor(android.R.color.system_accent1_100)
        } else {
            resources.getColor(android.R.color.system_accent2_600)
        }
        clock.setColors(DOZE_COLOR, color)
        clock.animateAppearOnLockscreen()
    }

    private fun recomputePadding() {
        val lp = largeClock.getLayoutParams() as FrameLayout.LayoutParams
        lp.topMargin = (-0.5f * largeClock.bottom).toInt()
        largeClock.setLayoutParams(lp)
    }

    override fun dump(pw: PrintWriter) = clocks.forEach { it.dump(pw) }

    companion object {
        @VisibleForTesting const val DOZE_COLOR = Color.WHITE
        private const val FORMAT_NUMBER = 1234567890
    }
}
