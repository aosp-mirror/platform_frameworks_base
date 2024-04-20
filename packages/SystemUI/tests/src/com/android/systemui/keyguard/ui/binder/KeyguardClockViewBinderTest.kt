/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.binder

import android.view.View
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.keyguard.KeyguardClockSwitch.SMALL
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockFaceLayout
import com.android.systemui.util.mockito.whenever
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardClockViewBinderTest : SysuiTestCase() {
    @Mock private lateinit var rootView: ConstraintLayout
    @Mock private lateinit var burnInLayer: Layer
    @Mock private lateinit var clock: ClockController
    @Mock private lateinit var largeClock: ClockFaceController
    @Mock private lateinit var smallClock: ClockFaceController
    @Mock private lateinit var largeClockView: View
    @Mock private lateinit var smallClockView: View
    @Mock private lateinit var smallClockFaceLayout: ClockFaceLayout
    @Mock private lateinit var largeClockFaceLayout: ClockFaceLayout
    @Mock private lateinit var clockViewModel: KeyguardClockViewModel
    private val clockSize = MutableStateFlow(ClockSize.LARGE)
    private val currentClock: MutableStateFlow<ClockController?> = MutableStateFlow(null)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(clockViewModel.clockSize).thenReturn(clockSize)
        whenever(clockViewModel.currentClock).thenReturn(currentClock)
        whenever(clockViewModel.burnInLayer).thenReturn(burnInLayer)
    }

    @Test
    fun addClockViews_WeatherClock() {
        setupWeatherClock()
        KeyguardClockViewBinder.addClockViews(clock, rootView)
        verify(rootView).addView(smallClockView)
        verify(rootView).addView(largeClockView)
    }

    @Test
    fun addClockViews_nonWeatherClock() {
        setupNonWeatherClock()
        KeyguardClockViewBinder.addClockViews(clock, rootView)
        verify(rootView).addView(smallClockView)
        verify(rootView).addView(largeClockView)
    }
    @Test
    fun addClockViewsToBurnInLayer_LargeWeatherClock() {
        setupWeatherClock()
        KeyguardClockViewBinder.updateBurnInLayer(rootView, clockViewModel, ClockSize.LARGE)
        verify(burnInLayer).removeView(smallClockView)
        verify(burnInLayer).addView(largeClockView)
    }

    @Test
    fun addClockViewsToBurnInLayer_LargeNonWeatherClock() {
        setupNonWeatherClock()
        KeyguardClockViewBinder.updateBurnInLayer(rootView, clockViewModel, ClockSize.LARGE)
        verify(burnInLayer).removeView(smallClockView)
        verify(burnInLayer, never()).addView(largeClockView)
    }

    @Test
    fun addClockViewsToBurnInLayer_SmallClock() {
        setupNonWeatherClock()
        KeyguardClockViewBinder.updateBurnInLayer(rootView, clockViewModel, ClockSize.SMALL)
        verify(burnInLayer).addView(smallClockView)
        verify(burnInLayer).removeView(largeClockView)
    }

    private fun setupWeatherClock() {
        setupClock()
        val clockConfig =
            ClockConfig(
                id = "WEATHER_CLOCK",
                name = "",
                description = "",
                useAlternateSmartspaceAODTransition = true,
                useCustomClockScene = true
            )
        whenever(clock.config).thenReturn(clockConfig)
    }

    private fun setupNonWeatherClock() {
        setupClock()
        val clockConfig = ClockConfig(id = "NON_WEATHER_CLOCK", name = "", description = "")
        whenever(clock.config).thenReturn(clockConfig)
    }

    private fun setupClock() {
        whenever(largeClockFaceLayout.views).thenReturn(listOf(largeClockView))
        whenever(smallClockFaceLayout.views).thenReturn(listOf(smallClockView))
        whenever(clock.largeClock).thenReturn(largeClock)
        whenever(clock.smallClock).thenReturn(smallClock)
        whenever(largeClock.layout).thenReturn(largeClockFaceLayout)
        whenever(smallClock.layout).thenReturn(smallClockFaceLayout)
        currentClock.value = clock
    }
}
