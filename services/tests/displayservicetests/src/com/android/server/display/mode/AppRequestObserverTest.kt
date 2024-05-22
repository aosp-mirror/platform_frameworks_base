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

package com.android.server.display.mode

import android.content.Context
import android.util.SparseArray
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.server.display.feature.DisplayManagerFlags
import com.android.server.display.mode.DisplayModeDirector.DisplayDeviceConfigProvider
import com.android.server.display.mode.RefreshRateVote.RenderVote
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(TestParameterInjector::class)
class AppRequestObserverTest {

    private lateinit var context: Context
    private val mockInjector = mock<DisplayModeDirector.Injector>()
    private val mockFlags = mock<DisplayManagerFlags>()
    private val mockDisplayDeviceConfigProvider = mock<DisplayDeviceConfigProvider>()
    private val testHandler = TestHandler(null)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `test app request votes`(@TestParameter testCase: AppRequestTestCase) {
        whenever(mockFlags.ignoreAppPreferredRefreshRateRequest())
                .thenReturn(testCase.ignoreRefreshRateRequest)
        val displayModeDirector = DisplayModeDirector(
            context, testHandler, mockInjector, mockFlags, mockDisplayDeviceConfigProvider)
        val modes = arrayOf(
            Display.Mode(1, 1000, 1000, 60f),
            Display.Mode(2, 1000, 1000, 90f),
            Display.Mode(3, 1000, 1000, 120f),
            Display.Mode(99, 1000, 1000, 45f, 45f, true, floatArrayOf(), intArrayOf())
        )

        displayModeDirector.injectAppSupportedModesByDisplay(
            SparseArray<Array<Display.Mode>>().apply {
                append(Display.DEFAULT_DISPLAY, modes)
            })
        displayModeDirector.injectDefaultModeByDisplay(SparseArray<Display.Mode>().apply {
            append(Display.DEFAULT_DISPLAY, modes[0])
        })

        displayModeDirector.appRequestObserver.setAppRequest(Display.DEFAULT_DISPLAY,
            testCase.modeId,
            testCase.requestedRefreshRates,
            testCase.requestedMinRefreshRates,
            testCase.requestedMaxRefreshRates)

        val baseModeVote = displayModeDirector.getVote(Display.DEFAULT_DISPLAY,
            Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE)
        assertThat(baseModeVote).isEqualTo(testCase.expectedBaseModeVote)

        val sizeVote = displayModeDirector.getVote(Display.DEFAULT_DISPLAY,
            Vote.PRIORITY_APP_REQUEST_SIZE)
        assertThat(sizeVote).isEqualTo(testCase.expectedSizeVote)

        val renderRateVote = displayModeDirector.getVote(Display.DEFAULT_DISPLAY,
                Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE)
        assertThat(renderRateVote).isEqualTo(testCase.expectedRenderRateVote)
    }

    enum class AppRequestTestCase(
        val ignoreRefreshRateRequest: Boolean,
        val modeId: Int,
        val requestedRefreshRates: Float,
        val requestedMinRefreshRates: Float,
        val requestedMaxRefreshRates: Float,
        internal val expectedBaseModeVote: Vote?,
        internal val expectedSizeVote: Vote?,
        internal val expectedRenderRateVote: Vote?,
    ) {
        BASE_MODE_60(true, 1, 0f, 0f, 0f,
            BaseModeRefreshRateVote(60f), SizeVote(1000, 1000, 1000, 1000), null),
        BASE_MODE_90(true, 2, 0f, 0f, 0f,
            BaseModeRefreshRateVote(90f), SizeVote(1000, 1000, 1000, 1000), null),
        MIN_REFRESH_RATE_60(true, 0, 0f, 60f, 0f,
            null, null, RenderVote(60f, Float.POSITIVE_INFINITY)),
        MIN_REFRESH_RATE_120(true, 0, 0f, 120f, 0f,
        null, null, RenderVote(120f, Float.POSITIVE_INFINITY)),
        MAX_REFRESH_RATE_60(true, 0, 0f, 0f, 60f,
            null, null, RenderVote(0f, 60f)),
        MAX_REFRESH_RATE_120(true, 0, 0f, 0f, 120f,
            null, null, RenderVote(0f, 120f)),
        INVALID_MIN_MAX_REFRESH_RATE(true, 0, 0f, 90f, 60f,
        null, null, null),
        BASE_MODE_MIN_MAX(true, 1, 0f, 60f, 90f,
            BaseModeRefreshRateVote(60f), SizeVote(1000, 1000, 1000, 1000), RenderVote(60f, 90f)),
        PREFERRED_REFRESH_RATE(false, 0, 60f, 0f, 0f,
            BaseModeRefreshRateVote(60f), SizeVote(1000, 1000, 1000, 1000), null),
        PREFERRED_REFRESH_RATE_IGNORED(true, 0, 60f, 0f, 0f,
            null, null, null),
        PREFERRED_REFRESH_RATE_INVALID(false, 0, 25f, 0f, 0f,
            null, null, null),
        SYNTHETIC_MODE(false, 99, 0f, 0f, 0f,
            RenderVote(45f, 45f), SizeVote(1000, 1000, 1000, 1000), null),
    }
}