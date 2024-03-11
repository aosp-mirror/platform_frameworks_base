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

import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class KeyguardClockViewModelWithKosmosTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = kosmos.keyguardClockViewModel
    private val testScope = kosmos.testScope

    @Test
    fun currentClockLayout_splitShadeOn_clockCentered_largeClock() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Split)
                keyguardRepository.setClockShouldBeCentered(true)
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
            }
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)
            assertThat(currentClockLayout).isEqualTo(KeyguardClockViewModel.ClockLayout.LARGE_CLOCK)
        }

    @Test
    fun currentClockLayout_splitShadeOn_clockNotCentered_largeClock_splitShadeLargeClock() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Split)
                keyguardRepository.setClockShouldBeCentered(false)
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
            }
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)
            assertThat(currentClockLayout)
                .isEqualTo(KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_LARGE_CLOCK)
        }

    @Test
    fun currentClockLayout_splitShadeOn_clockNotCentered_smallClock_splitShadeSmallClock() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Split)
                keyguardRepository.setClockShouldBeCentered(false)
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.SMALL)
            }
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)
            assertThat(currentClockLayout)
                .isEqualTo(KeyguardClockViewModel.ClockLayout.SPLIT_SHADE_SMALL_CLOCK)
        }

    @Test
    fun currentClockLayout_singleShade_smallClock_smallClock() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Single)
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.SMALL)
            }
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)
            assertThat(currentClockLayout).isEqualTo(KeyguardClockViewModel.ClockLayout.SMALL_CLOCK)
        }

    @Test
    fun currentClockLayout_singleShade_largeClock_largeClock() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Single)
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
            }
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)
            assertThat(currentClockLayout).isEqualTo(KeyguardClockViewModel.ClockLayout.LARGE_CLOCK)
        }
}
