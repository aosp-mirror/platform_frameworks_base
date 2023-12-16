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
import androidx.test.filters.SmallTest
import com.android.keyguard.ClockEventController
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth
import kotlin.test.Test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class KeyguardClockRepositoryTest : SysuiTestCase() {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var scope: TestScope

    private lateinit var underTest: KeyguardClockRepository
    private lateinit var fakeSettings: FakeSettings
    @Mock private lateinit var clockRegistry: ClockRegistry
    @Mock private lateinit var clockEventController: ClockEventController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeSettings = FakeSettings()
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        scope = TestScope(dispatcher)
        underTest =
            KeyguardClockRepositoryImpl(
                fakeSettings,
                clockRegistry,
                clockEventController,
                dispatcher,
                scope.backgroundScope
            )
    }

    @Test
    fun testSelectedClockSize_small() =
        scope.runTest {
            fakeSettings.putInt(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 0)
            val value = collectLastValue(underTest.selectedClockSize)
            Truth.assertThat(value()).isEqualTo(SettingsClockSize.SMALL)
        }

    @Test
    fun testSelectedClockSize_dynamic() =
        scope.runTest {
            fakeSettings.putInt(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1)
            val value = collectLastValue(underTest.selectedClockSize)
            Truth.assertThat(value()).isEqualTo(SettingsClockSize.DYNAMIC)
        }
}
