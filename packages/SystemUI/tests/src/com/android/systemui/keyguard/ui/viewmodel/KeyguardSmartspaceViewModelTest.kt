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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardSmartspaceRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardSmartspaceViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val underTest = kosmos.keyguardSmartspaceViewModel
    val res = context.resources

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var clockController: ClockController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        kosmos.fakeKeyguardClockRepository.setCurrentClock(clockController)
    }

    @Test
    fun testWhenWeatherEnabled_notCustomWeatherDataDisplay_isWeatherVisible_shouldBeTrue() =
        testScope.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            with(kosmos) {
                keyguardSmartspaceRepository.setIsWeatherEnabled(true)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(isWeatherVisible).isEqualTo(true)
        }

    @Test
    fun testWhenWeatherEnabled_hasCustomWeatherDataDisplay_isWeatherVisible_shouldBeFalse() =
        testScope.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay).thenReturn(true)

            with(kosmos) {
                keyguardSmartspaceRepository.setIsWeatherEnabled(true)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(isWeatherVisible).isEqualTo(false)
        }

    @Test
    fun testWhenWeatherEnabled_notCustomWeatherDataDisplay_notIsWeatherVisible_shouldBeFalse() =
        testScope.runTest {
            val isWeatherVisible by collectLastValue(underTest.isWeatherVisible)
            whenever(clockController.largeClock.config.hasCustomWeatherDataDisplay)
                .thenReturn(false)

            with(kosmos) {
                keyguardSmartspaceRepository.setIsWeatherEnabled(false)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(isWeatherVisible).isEqualTo(false)
        }
}
