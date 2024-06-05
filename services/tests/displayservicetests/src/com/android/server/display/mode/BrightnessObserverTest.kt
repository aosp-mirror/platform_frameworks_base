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

package com.android.server.display.mode

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.BrightnessInfo
import android.util.SparseArray
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.feature.DisplayManagerFlags
import com.android.server.display.mode.DisplayModeDirector.DisplayDeviceConfigProvider
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(TestParameterInjector::class)
class BrightnessObserverTest {

    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    private lateinit var spyContext: Context
    private val mockInjector = mock<DisplayModeDirector.Injector>()
    private val mockFlags = mock<DisplayManagerFlags>()
    private val mockDeviceConfig = mock<DisplayDeviceConfig>()
    private val mockDisplayDeviceConfigProvider = mock<DisplayDeviceConfigProvider>()

    private val testHandler = TestHandler(null)

    @Before
    fun setUp() {
        spyContext = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun testLowLightBlockingZoneVotes(@TestParameter testCase: LowLightTestCase) {
        setUpLowBrightnessZone()
        whenever(mockFlags.isVsyncLowLightVoteEnabled).thenReturn(testCase.vsyncLowLightVoteEnabled)
        val displayModeDirector = DisplayModeDirector(
                spyContext, testHandler, mockInjector, mockFlags, mockDisplayDeviceConfigProvider)
        val ddcByDisplay = SparseArray<DisplayDeviceConfig>()
        whenever(mockDeviceConfig.isVrrSupportEnabled).thenReturn(testCase.vrrSupported)
        ddcByDisplay.put(Display.DEFAULT_DISPLAY, mockDeviceConfig)
        displayModeDirector.injectDisplayDeviceConfigByDisplay(ddcByDisplay)
        val brightnessObserver = displayModeDirector.BrightnessObserver(
                spyContext, testHandler, mockInjector, mockFlags)

        brightnessObserver.onRefreshRateSettingChangedLocked(0.0f, 120.0f)
        brightnessObserver.updateBlockingZoneThresholds(mockDeviceConfig, false)
        brightnessObserver.onDeviceConfigRefreshRateInLowZoneChanged(60)

        brightnessObserver.onDisplayChanged(Display.DEFAULT_DISPLAY)

        assertThat(displayModeDirector.getVote(VotesStorage.GLOBAL_ID,
                Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH)).isEqualTo(testCase.expectedVote)
    }

    private fun setUpLowBrightnessZone() {
        whenever(mockInjector.getBrightnessInfo(Display.DEFAULT_DISPLAY)).thenReturn(
                BrightnessInfo(/* brightness = */ 0.05f, /* adjustedBrightness = */ 0.05f,
                        /* brightnessMinimum = */ 0.0f, /* brightnessMaximum = */ 1.0f,
                        BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                        /* highBrightnessTransitionPoint = */ 1.0f,
                        BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE))
        whenever(mockDeviceConfig.highDisplayBrightnessThresholds).thenReturn(floatArrayOf())
        whenever(mockDeviceConfig.highAmbientBrightnessThresholds).thenReturn(floatArrayOf())
        whenever(mockDeviceConfig.lowDisplayBrightnessThresholds).thenReturn(floatArrayOf(0.1f))
        whenever(mockDeviceConfig.lowAmbientBrightnessThresholds).thenReturn(floatArrayOf(10f))
    }

    enum class LowLightTestCase(
            val vrrSupported: Boolean,
            val vsyncLowLightVoteEnabled: Boolean,
            internal val expectedVote: Vote
    ) {
        ALL_ENABLED(true, true, CombinedVote(
                listOf(DisableRefreshRateSwitchingVote(true),
                        SupportedRefreshRatesVote(
                                listOf(SupportedRefreshRatesVote.RefreshRates(60f, 60f),
                                        SupportedRefreshRatesVote.RefreshRates(120f, 120f)))))),
        VRR_NOT_SUPPORTED(false, true, DisableRefreshRateSwitchingVote(true)),
        VSYNC_VOTE_DISABLED(true, false, DisableRefreshRateSwitchingVote(true))
    }
}