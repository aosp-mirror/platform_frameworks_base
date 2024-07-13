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
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.config.RefreshRateData
import com.android.server.display.config.SupportedModeData
import com.android.server.display.config.createRefreshRateData
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

private val LOW_LIGHT_REFRESH_RATE_DATA = createRefreshRateData(
    lowLightBlockingZoneSupportedModes = listOf(
        SupportedModeData(60f, 60f), SupportedModeData(240f, 240f)))
private val EXPECTED_SUPPORTED_MODES_VOTE = SupportedRefreshRatesVote(
    listOf(SupportedRefreshRatesVote.RefreshRates(60f, 60f),
        SupportedRefreshRatesVote.RefreshRates(240f, 240f)))

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
        whenever(mockDeviceConfig.isVrrSupportEnabled).thenReturn(testCase.vrrSupported)
        whenever(mockDeviceConfig.refreshRateData).thenReturn(testCase.refreshRateData)
        whenever(mockDeviceConfig.defaultLowBlockingZoneRefreshRate).thenReturn(-1)

        displayModeDirector.defaultDisplayDeviceUpdated(mockDeviceConfig)

        val brightnessObserver = displayModeDirector.BrightnessObserver(
                spyContext, testHandler, mockInjector, mockFlags)
        // set mRefreshRateChangeable to true
        brightnessObserver.onRefreshRateSettingChangedLocked(0.0f, 120.0f)
        brightnessObserver.updateBlockingZoneThresholds(mockDeviceConfig, false)
        brightnessObserver.onDeviceConfigRefreshRateInLowZoneChanged(testCase.refreshRateInLowZone)

        brightnessObserver.onDisplayChanged(Display.DEFAULT_DISPLAY)

        assertThat(displayModeDirector.getVote(VotesStorage.GLOBAL_ID,
            Vote.PRIORITY_FLICKER_REFRESH_RATE)).isEqualTo(testCase.expectedRefreshRateVote)
        assertThat(displayModeDirector.getVote(VotesStorage.GLOBAL_ID,
                Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH)).isEqualTo(testCase.expectedSwitchVote)
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
            val refreshRateData: RefreshRateData,
            val refreshRateInLowZone: Int,
            internal val expectedRefreshRateVote: Vote,
            internal val expectedSwitchVote: Vote?,
    ) {
        ALL_ENABLED(true, true, LOW_LIGHT_REFRESH_RATE_DATA, 60,
            EXPECTED_SUPPORTED_MODES_VOTE, null),
        ALL_ENABLED_NO_RR_IN_LOW_ZONE(true, true, LOW_LIGHT_REFRESH_RATE_DATA, 0,
            EXPECTED_SUPPORTED_MODES_VOTE, null),
        VRR_NOT_SUPPORTED(false, true, LOW_LIGHT_REFRESH_RATE_DATA, 60,
            Vote.forPhysicalRefreshRates(60f, 60f), DisableRefreshRateSwitchingVote(true)),
        VSYNC_VOTE_DISABLED(true, false, LOW_LIGHT_REFRESH_RATE_DATA, 50,
            Vote.forPhysicalRefreshRates(50f, 50f), DisableRefreshRateSwitchingVote(true)),
        NO_LOW_LIGHT_CONFIG(true, true, createRefreshRateData(), 40,
            Vote.forPhysicalRefreshRates(40f, 40f), DisableRefreshRateSwitchingVote(true)),
    }
}