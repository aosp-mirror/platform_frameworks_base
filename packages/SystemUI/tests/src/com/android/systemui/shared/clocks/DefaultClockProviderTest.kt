/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shared.clocks

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.plugins.clocks.ClockSettings
import com.android.systemui.shared.clocks.DefaultClockController.Companion.DOZE_COLOR
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import java.util.Locale
import java.util.TimeZone
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.notNull
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private fun DefaultClockProvider.createClock(id: ClockId): DefaultClockController =
    createClock(ClockSettings(id, null)) as DefaultClockController

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultClockProviderTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Mock private lateinit var mockSmallClockView: AnimatableClockView
    @Mock private lateinit var mockLargeClockView: AnimatableClockView
    @Mock private lateinit var layoutInflater: LayoutInflater
    @Mock private lateinit var mockClockThumbnail: Drawable
    @Mock private lateinit var resources: Resources
    private lateinit var provider: DefaultClockProvider

    @Before
    fun setUp() {
        whenever(layoutInflater.inflate(eq(R.layout.clock_default_small), any(), anyBoolean()))
            .thenReturn(mockSmallClockView)
        whenever(layoutInflater.inflate(eq(R.layout.clock_default_large), any(), anyBoolean()))
            .thenReturn(mockLargeClockView)
        whenever(resources.getDrawable(R.drawable.clock_default_thumbnail, null))
            .thenReturn(mockClockThumbnail)
        whenever(mockSmallClockView.getLayoutParams()).thenReturn(FrameLayout.LayoutParams(10, 10))
        whenever(mockLargeClockView.getLayoutParams()).thenReturn(FrameLayout.LayoutParams(10, 10))

        provider = DefaultClockProvider(context, layoutInflater, resources)
    }

    @Test
    fun providedClocks_matchesFactory() {
        // All providers need to provide clocks & thumbnails for exposed clocks
        for (metadata in provider.getClocks()) {
            assertNotNull(provider.createClock(metadata.clockId))
            assertNotNull(provider.getClockThumbnail(metadata.clockId))
        }
    }

    @Test
    fun defaultClock_alwaysProvided() {
        // Default clock provider must always provide the default clock
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        assertNotNull(clock)
        assertEquals(mockSmallClockView, clock.smallClock.view)
        assertEquals(mockLargeClockView, clock.largeClock.view)
    }

    @Test
    fun defaultClock_initialize() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        verify(mockSmallClockView).setColors(DOZE_COLOR, Color.MAGENTA)
        verify(mockLargeClockView).setColors(DOZE_COLOR, Color.MAGENTA)

        clock.initialize(resources, 0f, 0f)

        val expectedColor = 0
        verify(mockSmallClockView).setColors(DOZE_COLOR, expectedColor)
        verify(mockLargeClockView).setColors(DOZE_COLOR, expectedColor)
        verify(mockSmallClockView).onTimeZoneChanged(notNull())
        verify(mockLargeClockView).onTimeZoneChanged(notNull())
        verify(mockSmallClockView).refreshTime()
        verify(mockLargeClockView).refreshTime()
        verify(mockLargeClockView).setLayoutParams(any())
    }

    @Test
    fun defaultClock_events_onTimeTick() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.smallClock.events.onTimeTick()
        clock.largeClock.events.onTimeTick()

        verify(mockSmallClockView).refreshTime()
        verify(mockLargeClockView).refreshTime()
    }

    @Test
    fun defaultClock_events_onTimeFormatChanged() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.events.onTimeFormatChanged(true)

        verify(mockSmallClockView).refreshFormat(true)
        verify(mockLargeClockView).refreshFormat(true)
    }

    @Test
    fun defaultClock_events_onTimeZoneChanged() {
        val timeZone = mock<TimeZone>()
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.events.onTimeZoneChanged(timeZone)

        verify(mockSmallClockView).onTimeZoneChanged(timeZone)
        verify(mockLargeClockView).onTimeZoneChanged(timeZone)
    }

    @Test
    fun defaultSmallClock_events_onFontSettingChanged() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.smallClock.events.onFontSettingChanged(100f)

        verify(mockSmallClockView).setTextSize(eq(TypedValue.COMPLEX_UNIT_PX), eq(100f))
    }

    @Test
    fun defaultLargeClock_events_onFontSettingChanged() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.largeClock.events.onFontSettingChanged(200f)

        verify(mockLargeClockView).setTextSize(eq(TypedValue.COMPLEX_UNIT_PX), eq(200f))
        verify(mockLargeClockView).setLayoutParams(any())
    }

    @Test
    fun defaultClock_events_onColorPaletteChanged() {
        val expectedColor = 0
        val clock = provider.createClock(DEFAULT_CLOCK_ID)

        verify(mockSmallClockView).setColors(DOZE_COLOR, Color.MAGENTA)
        verify(mockLargeClockView).setColors(DOZE_COLOR, Color.MAGENTA)

        clock.events.onColorPaletteChanged(resources)

        verify(mockSmallClockView).setColors(DOZE_COLOR, expectedColor)
        verify(mockLargeClockView).setColors(DOZE_COLOR, expectedColor)
    }

    @Test
    fun defaultClock_events_onSeedColorChanged() {
        val initSeedColor = 10
        val newSeedColor = 20
        val clock = provider.createClock(ClockSettings(DEFAULT_CLOCK_ID, initSeedColor))

        verify(mockSmallClockView).setColors(DOZE_COLOR, initSeedColor)
        verify(mockLargeClockView).setColors(DOZE_COLOR, initSeedColor)

        clock.events.onSeedColorChanged(newSeedColor)

        verify(mockSmallClockView).setColors(DOZE_COLOR, newSeedColor)
        verify(mockLargeClockView).setColors(DOZE_COLOR, newSeedColor)
    }

    @Test
    fun defaultClock_events_onLocaleChanged() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.events.onLocaleChanged(Locale.getDefault())

        verify(mockSmallClockView, times(2)).setLineSpacingScale(anyFloat())
        verify(mockLargeClockView, times(2)).setLineSpacingScale(anyFloat())
        verify(mockSmallClockView, times(2)).refreshFormat()
        verify(mockLargeClockView, times(2)).refreshFormat()
    }

    @Test
    fun test_aodClock_always_whiteColor() {
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        clock.smallClock.animations.doze(0.9f) // set AOD mode to active
        clock.smallClock.events.onRegionDarknessChanged(true)
        verify((clock.smallClock.view as AnimatableClockView), never()).animateAppearOnLockscreen()
    }
}
