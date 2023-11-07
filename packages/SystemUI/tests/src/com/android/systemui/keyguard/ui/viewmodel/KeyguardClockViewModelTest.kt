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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.provider.Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK
import androidx.test.filters.SmallTest
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.keyguard.KeyguardClockSwitch.SMALL
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.KeyguardClockRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
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

@SmallTest
@RunWith(JUnit4::class)
class KeyguardClockViewModelTest : SysuiTestCase() {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var scope: TestScope

    private lateinit var underTest: KeyguardClockViewModel
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var keyguardRepository: KeyguardRepository
    private lateinit var keyguardClockInteractor: KeyguardClockInteractor
    private lateinit var keyguardClockRepository: KeyguardClockRepository
    private lateinit var fakeSettings: FakeSettings
    @Mock private lateinit var clockRegistry: ClockRegistry
    @Mock private lateinit var eventController: ClockEventController
    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        KeyguardInteractorFactory.create().let {
            keyguardInteractor = it.keyguardInteractor
            keyguardRepository = it.repository
        }
        fakeSettings = FakeSettings()
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        scope = TestScope(dispatcher)
        keyguardClockRepository = KeyguardClockRepository(fakeSettings, clockRegistry, dispatcher)
        keyguardClockInteractor = KeyguardClockInteractor(eventController, keyguardClockRepository)
        underTest =
            KeyguardClockViewModel(
                keyguardInteractor,
                keyguardClockInteractor,
                scope.backgroundScope
            )
    }

    @Test
    fun testClockSize_alwaysSmallClock() =
        scope.runTest {
            // When use double line clock is disabled,
            // should always return small
            fakeSettings.putInt(LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 0)
            keyguardRepository.setClockSize(LARGE)
            val value = collectLastValue(underTest.clockSize)
            assertThat(value()).isEqualTo(SMALL)
        }

    @Test
    fun testClockSize_dynamicClockSize() =
        scope.runTest {
            fakeSettings.putInt(LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1)
            keyguardRepository.setClockSize(SMALL)
            var value = collectLastValue(underTest.clockSize)
            assertThat(value()).isEqualTo(SMALL)

            keyguardRepository.setClockSize(LARGE)
            value = collectLastValue(underTest.clockSize)
            assertThat(value()).isEqualTo(LARGE)
        }
}
