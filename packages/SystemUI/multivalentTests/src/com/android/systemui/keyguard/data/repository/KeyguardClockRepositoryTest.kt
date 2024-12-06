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

package com.android.systemui.keyguard.data.repository

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.ClockEventController
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class KeyguardClockRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val dispatcher = kosmos.testDispatcher
    private val scope = kosmos.testScope
    private val fakeSettings = kosmos.fakeSettings

    private lateinit var underTest: KeyguardClockRepository
    @Mock private lateinit var clockRegistry: ClockRegistry
    @Mock private lateinit var clockEventController: ClockEventController
    private val fakeFeatureFlagsClassic = FakeFeatureFlagsClassic()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            KeyguardClockRepositoryImpl(
                fakeSettings,
                clockRegistry,
                clockEventController,
                dispatcher,
                scope.backgroundScope,
                context,
                fakeFeatureFlagsClassic,
            )
    }

    @Test
    fun testSelectedClockSize_small() =
        scope.runTest {
            fakeSettings.putInt(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 0)
            val value = collectLastValue(underTest.selectedClockSize)
            Truth.assertThat(value()).isEqualTo(ClockSizeSetting.SMALL)
        }

    @Test
    fun testSelectedClockSize_dynamic() =
        scope.runTest {
            fakeSettings.putInt(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1)
            val value = collectLastValue(underTest.selectedClockSize)
            Truth.assertThat(value()).isEqualTo(ClockSizeSetting.DYNAMIC)
        }

    @Test
    fun testShouldForceSmallClock() =
        scope.runTest {
            overrideResource(R.bool.force_small_clock_on_lockscreen, true)
            fakeFeatureFlagsClassic.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, true)
            Truth.assertThat(underTest.shouldForceSmallClock).isTrue()
        }
}
