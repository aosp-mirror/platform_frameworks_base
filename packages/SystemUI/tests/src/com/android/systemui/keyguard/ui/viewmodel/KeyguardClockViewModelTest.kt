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
import com.android.systemui.keyguard.data.repository.KeyguardClockRepositoryImpl
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
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
    @Mock private lateinit var clock: ClockController
    @Mock private lateinit var largeClock: ClockFaceController
    @Mock private lateinit var clockFaceConfig: ClockFaceConfig
    @Mock private lateinit var eventController: ClockEventController
    @Mock private lateinit var splitShadeStateController: SplitShadeStateController
    @Mock private lateinit var notifsKeyguardInteractor: NotificationsKeyguardInteractor
    @Mock private lateinit var areNotificationsFullyHidden: Flow<Boolean>

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
        setupMockClock()
        keyguardClockRepository =
            KeyguardClockRepositoryImpl(
                fakeSettings,
                clockRegistry,
                eventController,
                dispatcher,
                scope.backgroundScope
            )
        keyguardClockInteractor = KeyguardClockInteractor(keyguardClockRepository)
        whenever(notifsKeyguardInteractor.areNotificationsFullyHidden)
            .thenReturn(areNotificationsFullyHidden)
        underTest =
            KeyguardClockViewModel(
                keyguardInteractor,
                keyguardClockInteractor,
                scope.backgroundScope,
                splitShadeStateController,
                notifsKeyguardInteractor
            )
    }

    @Test
    fun testClockSize_alwaysSmallClock() =
        scope.runTest {
            // When use double line clock is disabled,
            // should always return small
            fakeSettings.putInt(LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 0)
            keyguardClockRepository.setClockSize(LARGE)
            val value = collectLastValue(underTest.clockSize)
            assertThat(value()).isEqualTo(SMALL)
        }

    @Test
    fun testClockSize_dynamicClockSize() =
        scope.runTest {
            fakeSettings.putInt(LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1)
            keyguardClockRepository.setClockSize(SMALL)
            var value = collectLastValue(underTest.clockSize)
            assertThat(value()).isEqualTo(SMALL)

            keyguardClockRepository.setClockSize(LARGE)
            value = collectLastValue(underTest.clockSize)
            assertThat(value()).isEqualTo(LARGE)
        }

    @Test
    fun isLargeClockVisible_whenLargeClockSize_isTrue() =
        scope.runTest {
            fakeSettings.putInt(LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1)
            keyguardClockRepository.setClockSize(LARGE)
            var value = collectLastValue(underTest.isLargeClockVisible)
            assertThat(value()).isEqualTo(true)
        }

    @Test
    fun isLargeClockVisible_whenSmallClockSize_isFalse() =
        scope.runTest {
            fakeSettings.putInt(LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1)
            keyguardClockRepository.setClockSize(SMALL)
            var value = collectLastValue(underTest.isLargeClockVisible)
            assertThat(value()).isEqualTo(false)
        }

    private fun setupMockClock() {
        whenever(clock.largeClock).thenReturn(largeClock)
        whenever(largeClock.config).thenReturn(clockFaceConfig)
        whenever(clockFaceConfig.hasCustomWeatherDataDisplay).thenReturn(false)
        whenever(clockRegistry.createCurrentClock()).thenReturn(clock)
    }
}
