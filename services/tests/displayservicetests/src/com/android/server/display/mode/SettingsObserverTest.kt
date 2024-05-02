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
import android.provider.Settings
import android.util.SparseArray
import android.view.Display
import android.view.SurfaceControl.RefreshRateRange
import android.view.SurfaceControl.RefreshRateRanges
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.internal.util.test.FakeSettingsProvider
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.feature.DisplayManagerFlags
import com.android.server.display.mode.DisplayModeDirector.DisplayDeviceConfigProvider
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

private val RANGE_NO_LIMIT = RefreshRateRange(0f, Float.POSITIVE_INFINITY)
private val RANGE_0_60 = RefreshRateRange(0f, 60f)
private val RANGE_0_90 = RefreshRateRange(0f, 90f)
private val RANGE_0_120 = RefreshRateRange(0f, 120f)
private val RANGE_60_90 = RefreshRateRange(60f, 90f)
private val RANGE_60_120 = RefreshRateRange(60f, 120f)
private val RANGE_60_INF = RefreshRateRange(60f, Float.POSITIVE_INFINITY)
private val RANGE_90_90 = RefreshRateRange(90f, 90f)
private val RANGE_90_120 = RefreshRateRange(90f, 120f)
private val RANGE_90_INF = RefreshRateRange(90f, Float.POSITIVE_INFINITY)

private val RANGES_NO_LIMIT = RefreshRateRanges(RANGE_NO_LIMIT, RANGE_NO_LIMIT)
private val RANGES_NO_LIMIT_60 = RefreshRateRanges(RANGE_NO_LIMIT, RANGE_0_60)
private val RANGES_NO_LIMIT_90 = RefreshRateRanges(RANGE_NO_LIMIT, RANGE_0_90)
private val RANGES_NO_LIMIT_120 = RefreshRateRanges(RANGE_NO_LIMIT, RANGE_0_120)
private val RANGES_90 = RefreshRateRanges(RANGE_0_90, RANGE_0_90)
private val RANGES_120 = RefreshRateRanges(RANGE_0_120, RANGE_0_120)
private val RANGES_90_60 = RefreshRateRanges(RANGE_0_90, RANGE_0_60)
private val RANGES_90TO90 = RefreshRateRanges(RANGE_90_90, RANGE_90_90)
private val RANGES_90TO120 = RefreshRateRanges(RANGE_90_120, RANGE_90_120)
private val RANGES_60TO120_60TO90 = RefreshRateRanges(RANGE_60_120, RANGE_60_90)
private val RANGES_MIN90 = RefreshRateRanges(RANGE_90_INF, RANGE_90_INF)
private val RANGES_MIN90_90TO120 = RefreshRateRanges(RANGE_90_INF, RANGE_90_120)
private val RANGES_MIN60_60TO90 = RefreshRateRanges(RANGE_60_INF, RANGE_60_90)
private val RANGES_MIN90_90TO90 = RefreshRateRanges(RANGE_90_INF, RANGE_90_90)

@SmallTest
@RunWith(TestParameterInjector::class)
class SettingsObserverTest {
    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    @get:Rule
    val settingsProviderRule = FakeSettingsProvider.rule()

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
    fun `test low power mode`(@TestParameter testCase: LowPowerTestCase) {
        whenever(mockFlags.isVsyncLowPowerVoteEnabled).thenReturn(testCase.vsyncLowPowerVoteEnabled)
        whenever(spyContext.contentResolver)
                .thenReturn(settingsProviderRule.mockContentResolver(null))
        val lowPowerModeSetting = if (testCase.lowPowerModeEnabled) 1 else 0
        Settings.Global.putInt(
                spyContext.contentResolver, Settings.Global.LOW_POWER_MODE, lowPowerModeSetting)

        val displayModeDirector = DisplayModeDirector(
                spyContext, testHandler, mockInjector, mockFlags, mockDisplayDeviceConfigProvider)
        val ddcByDisplay = SparseArray<DisplayDeviceConfig>()
        whenever(mockDeviceConfig.isVrrSupportEnabled).thenReturn(testCase.vrrSupported)
        ddcByDisplay.put(Display.DEFAULT_DISPLAY, mockDeviceConfig)
        displayModeDirector.injectDisplayDeviceConfigByDisplay(ddcByDisplay)
        val settingsObserver = displayModeDirector.SettingsObserver(
                spyContext, testHandler, mockFlags)

        settingsObserver.onChange(
                false, Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE), 1)

        assertThat(displayModeDirector.getVote(VotesStorage.GLOBAL_ID,
                Vote.PRIORITY_LOW_POWER_MODE)).isEqualTo(testCase.expectedVote)
    }

    enum class LowPowerTestCase(
        val vrrSupported: Boolean,
        val vsyncLowPowerVoteEnabled: Boolean,
        val lowPowerModeEnabled: Boolean,
        internal val expectedVote: Vote?
    ) {
        ALL_ENABLED(true, true, true,
            SupportedRefreshRatesVote(listOf(
                SupportedRefreshRatesVote.RefreshRates(60f, 240f),
                SupportedRefreshRatesVote.RefreshRates(60f, 60f)
            ))),
        LOW_POWER_OFF(true, true, false, null),
        DVRR_NOT_SUPPORTED_LOW_POWER_ON(false, true, true,
            RefreshRateVote.RenderVote(0f, 60f)),
        DVRR_NOT_SUPPORTED_LOW_POWER_OFF(false, true, false, null),
        VSYNC_VOTE_DISABLED_SUPPORTED_LOW_POWER_ON(true, false, true,
            RefreshRateVote.RenderVote(0f, 60f)),
        VSYNC_VOTE_DISABLED_LOW_POWER_OFF(true, false, false, null),
    }

    @Test
    fun `test settings refresh rates`(@TestParameter testCase: SettingsRefreshRateTestCase) {
        whenever(mockFlags.isPeakRefreshRatePhysicalLimitEnabled)
                .thenReturn(testCase.peakRefreshRatePhysicalLimitEnabled)

        val displayModeDirector = DisplayModeDirector(
            spyContext, testHandler, mockInjector, mockFlags, mockDisplayDeviceConfigProvider)

        val modes = arrayOf(
            Display.Mode(1, 1000, 1000, 60f),
            Display.Mode(2, 1000, 1000, 90f),
            Display.Mode(3, 1000, 1000, 120f)
        )
        displayModeDirector.injectSupportedModesByDisplay(SparseArray<Array<Display.Mode>>().apply {
            append(Display.DEFAULT_DISPLAY, modes)
        })
        displayModeDirector.injectDefaultModeByDisplay(SparseArray<Display.Mode>().apply {
            append(Display.DEFAULT_DISPLAY, modes[0])
        })

        val specs = displayModeDirector.getDesiredDisplayModeSpecsWithInjectedFpsSettings(
            testCase.minRefreshRate, testCase.peakRefreshRate, testCase.defaultRefreshRate)

        assertWithMessage("Primary RefreshRateRanges: ")
                .that(specs.primary).isEqualTo(testCase.expectedPrimaryRefreshRateRanges)
        assertWithMessage("App RefreshRateRanges: ")
                .that(specs.appRequest).isEqualTo(testCase.expectedAppRefreshRateRanges)
    }

    /**
     * Votes considered:
     * priority: PRIORITY_USER_SETTING_PEAK_REFRESH_RATE (also used for appRanged)
     * condition: peakRefreshRatePhysicalLimitEnabled, peakRR > 0
     * vote: physical(minRR, peakRR)
     *
     * priority: PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE (also used for appRanged)
     * condition: peakRR > 0
     * vote: render(minRR, peakRR)
     *
     * priority: PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE
     * condition: -
     * vote: render(minRR, INF)
     *
     * priority: PRIORITY_DEFAULT_RENDER_FRAME_RATE
     * condition: defaultRR > 0
     * vote: render(0, defaultRR)
     *
     * 0 considered not set
     *
     * For this test:
     * primary physical rate:
     *          (minRR, peakRefreshRatePhysicalLimitEnabled ? max(minRR, peakRR) : INF)
     * primary render rate : (minRR, min(defaultRR, max(minRR, peakRR)))
     *
     * app physical rate: (0, peakRefreshRatePhysicalLimitEnabled ? max(minRR, peakRR) : INF)
     * app render rate: (0, max(minRR, peakRR))
     */
    enum class SettingsRefreshRateTestCase(
        val minRefreshRate: Float,
        val peakRefreshRate: Float,
        val defaultRefreshRate: Float,
        val peakRefreshRatePhysicalLimitEnabled: Boolean,
        val expectedPrimaryRefreshRateRanges: RefreshRateRanges,
        val expectedAppRefreshRateRanges: RefreshRateRanges,
    ) {
        NO_LIMIT(0f, 0f, 0f, false, RANGES_NO_LIMIT, RANGES_NO_LIMIT),
        NO_LIMIT_WITH_PHYSICAL_RR(0f, 0f, 0f, true, RANGES_NO_LIMIT, RANGES_NO_LIMIT),

        LIMITS_0_0_90(0f, 0f, 90f, false, RANGES_NO_LIMIT_90, RANGES_NO_LIMIT),
        LIMITS_0_0_90_WITH_PHYSICAL_RR(0f, 0f, 90f, true, RANGES_NO_LIMIT_90, RANGES_NO_LIMIT),

        LIMITS_0_90_0(0f, 90f, 0f, false, RANGES_NO_LIMIT_90, RANGES_NO_LIMIT_90),
        LIMITS_0_90_0_WITH_PHYSICAL_RR(0f, 90f, 0f, true, RANGES_90, RANGES_90),

        LIMITS_0_90_60(0f, 90f, 60f, false, RANGES_NO_LIMIT_60, RANGES_NO_LIMIT_90),
        LIMITS_0_90_60_WITH_PHYSICAL_RR(0f, 90f, 60f, true, RANGES_90_60, RANGES_90),

        LIMITS_0_90_120(0f, 90f, 120f, false, RANGES_NO_LIMIT_90, RANGES_NO_LIMIT_90),
        LIMITS_0_90_120_WITH_PHYSICAL_RR(0f, 90f, 120f, true, RANGES_90, RANGES_90),

        LIMITS_90_0_0(90f, 0f, 0f, false, RANGES_MIN90, RANGES_NO_LIMIT),
        LIMITS_90_0_0_WITH_PHYSICAL_RR(90f, 0f, 0f, true, RANGES_MIN90, RANGES_NO_LIMIT),

        LIMITS_90_0_120(90f, 0f, 120f, false, RANGES_MIN90_90TO120, RANGES_NO_LIMIT),
        LIMITS_90_0_120_WITH_PHYSICAL_RR(90f,
            0f,
            120f,
            true,
            RANGES_MIN90_90TO120,
            RANGES_NO_LIMIT),

        LIMITS_90_0_60(90f, 0f, 60f, false, RANGES_MIN90, RANGES_NO_LIMIT),
        LIMITS_90_0_60_WITH_PHYSICAL_RR(90f, 0f, 60f, true, RANGES_MIN90, RANGES_NO_LIMIT),

        LIMITS_90_120_0(90f, 120f, 0f, false, RANGES_MIN90_90TO120, RANGES_NO_LIMIT_120),
        LIMITS_90_120_0_WITH_PHYSICAL_RR(90f, 120f, 0f, true, RANGES_90TO120, RANGES_120),

        LIMITS_90_60_0(90f, 60f, 0f, false, RANGES_MIN90_90TO90, RANGES_NO_LIMIT_90),
        LIMITS_90_60_0_WITH_PHYSICAL_RR(90f, 60f, 0f, true, RANGES_90TO90, RANGES_90),

        LIMITS_60_120_90(60f, 120f, 90f, false, RANGES_MIN60_60TO90, RANGES_NO_LIMIT_120),
        LIMITS_60_120_90_WITH_PHYSICAL_RR(60f, 120f, 90f, true, RANGES_60TO120_60TO90, RANGES_120),
    }
}