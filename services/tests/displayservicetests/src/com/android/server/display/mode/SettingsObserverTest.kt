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
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.internal.util.test.FakeSettingsProvider
import com.android.server.display.feature.DisplayManagerFlags
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
class SettingsObserverTest {

    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    @get:Rule
    val settingsProviderRule = FakeSettingsProvider.rule()

    private lateinit var spyContext: Context
    private val mockInjector = mock<DisplayModeDirector.Injector>()
    private val mockFlags = mock<DisplayManagerFlags>()

    private val testHandler = TestHandler(null)

    @Before
    fun setUp() {
        spyContext = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun testLowPowerMode(@TestParameter testCase: SettingsObserverTestCase) {
        whenever(mockFlags.isVsyncLowPowerVoteEnabled).thenReturn(testCase.vsyncLowPowerVoteEnabled)
        whenever(spyContext.contentResolver)
                .thenReturn(settingsProviderRule.mockContentResolver(null))
        val lowPowerModeSetting = if (testCase.lowPowerModeEnabled) 1 else 0
        Settings.Global.putInt(
                spyContext.contentResolver, Settings.Global.LOW_POWER_MODE, lowPowerModeSetting)

        val displayModeDirector = DisplayModeDirector(
                spyContext, testHandler, mockInjector, mockFlags)
        val settingsObserver = displayModeDirector.SettingsObserver(
                spyContext, testHandler, testCase.dvrrSupported, mockFlags)

        settingsObserver.onChange(
                false, Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE), 1)

        assertThat(displayModeDirector.getVote(VotesStorage.GLOBAL_ID,
                Vote.PRIORITY_LOW_POWER_MODE)).isEqualTo(testCase.expectedVote)
    }

    enum class SettingsObserverTestCase(
            val dvrrSupported: Boolean,
            val vsyncLowPowerVoteEnabled: Boolean,
            val lowPowerModeEnabled: Boolean,
            internal val expectedVote: Vote?
    ) {
        ALL_ENABLED(true, true, true,
                SupportedModesVote(listOf(
                        SupportedModesVote.SupportedMode(60f, 240f),
                        SupportedModesVote.SupportedMode(60f, 60f)
                ))),
        LOW_POWER_OFF(true, true, false, null),
        DVRR_NOT_SUPPORTED_LOW_POWER_ON(false, true, true,
                RefreshRateVote.RenderVote(0f, 60f)),
        DVRR_NOT_SUPPORTED_LOW_POWER_OFF(false, true, false, null),
        VSYNC_VOTE_DISABLED_SUPPORTED_LOW_POWER_ON(true, false, true,
                RefreshRateVote.RenderVote(0f, 60f)),
        VSYNC_VOTE_DISABLED_LOW_POWER_OFF(true, false, false, null),
    }
}