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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.android.systemui.util.Utils
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock

@SmallTest
@RunWith(JUnit4::class)
class KeyguardClockViewModelTest : SysuiTestCase() {
    private lateinit var kosmos: Kosmos
    private lateinit var underTest: KeyguardClockViewModel
    private lateinit var testScope: TestScope
    private lateinit var clockController: ClockController
    private lateinit var config: ClockFaceConfig

    @Before
    fun setup() {
        kosmos = testKosmos()
        testScope = kosmos.testScope
        underTest = kosmos.keyguardClockViewModel

        clockController = mock(ClockController::class.java)
        val largeClock = mock(ClockFaceController::class.java)
        config = mock(ClockFaceConfig::class.java)

        whenever(clockController.largeClock).thenReturn(largeClock)
        whenever(largeClock.config).thenReturn(config)
    }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
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
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
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
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
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
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
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
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun currentClockLayout_singleShade_largeClock_largeClock() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeMode(ShadeMode.Single)
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
            }
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)
            assertThat(currentClockLayout).isEqualTo(KeyguardClockViewModel.ClockLayout.LARGE_CLOCK)
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun hasCustomPositionUpdatedAnimation_withConfigTrue_isTrue() =
        testScope.runTest {
            with(kosmos) {
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
                whenever(config.hasCustomPositionUpdatedAnimation).thenReturn(true)
                fakeKeyguardClockRepository.setCurrentClock(clockController)
            }

            val hasCustomPositionUpdatedAnimation by
                collectLastValue(underTest.hasCustomPositionUpdatedAnimation)
            assertThat(hasCustomPositionUpdatedAnimation).isEqualTo(true)
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun hasCustomPositionUpdatedAnimation_withConfigFalse_isFalse() =
        testScope.runTest {
            with(kosmos) {
                keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)

                whenever(config.hasCustomPositionUpdatedAnimation).thenReturn(false)
                fakeKeyguardClockRepository.setCurrentClock(clockController)
            }

            val hasCustomPositionUpdatedAnimation by
                collectLastValue(underTest.hasCustomPositionUpdatedAnimation)
            assertThat(hasCustomPositionUpdatedAnimation).isEqualTo(false)
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun testClockSize_alwaysSmallClockSize() =
        testScope.runTest {
            kosmos.fakeKeyguardClockRepository.setSelectedClockSize(SettingsClockSize.SMALL)
            kosmos.keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)

            val value by collectLastValue(underTest.clockSize)
            assertThat(value).isEqualTo(KeyguardClockSwitch.SMALL)
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun testClockSize_dynamicClockSize() =
        testScope.runTest {
            kosmos.keyguardClockRepository.setClockSize(KeyguardClockSwitch.SMALL)
            kosmos.fakeKeyguardClockRepository.setSelectedClockSize(SettingsClockSize.DYNAMIC)
            val value by collectLastValue(underTest.clockSize)
            assertThat(value).isEqualTo(KeyguardClockSwitch.SMALL)

            kosmos.keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
            assertThat(value).isEqualTo(KeyguardClockSwitch.LARGE)
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun isLargeClockVisible_whenLargeClockSize_isTrue() =
        testScope.runTest {
            kosmos.keyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
            val value by collectLastValue(underTest.isLargeClockVisible)
            assertThat(value).isEqualTo(true)
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun isLargeClockVisible_whenSmallClockSize_isFalse() =
        testScope.runTest {
            kosmos.keyguardClockRepository.setClockSize(KeyguardClockSwitch.SMALL)
            val value by collectLastValue(underTest.isLargeClockVisible)
            assertThat(value).isEqualTo(false)
        }

    @Test
    @EnableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
    fun testSmallClockTop_splitShade_composeLockscreenOn() =
        testScope.runTest {
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            assertThat(underTest.getSmallClockTopMargin(context))
                .isEqualTo(
                    context.resources.getDimensionPixelSize(
                        R.dimen.keyguard_split_shade_top_margin
                    ) - Utils.getStatusBarHeaderHeightKeyguard(context)
                )
        }

    @Test
    @DisableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
    fun testSmallClockTop_splitShade_composeLockscreenOff() =
        testScope.runTest {
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            assertThat(underTest.getSmallClockTopMargin(context))
                .isEqualTo(
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
    fun testSmallClockTop_nonSplitShade_composeLockscreenOn() =
        testScope.runTest {
            assertThat(underTest.getSmallClockTopMargin(context))
                .isEqualTo(
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin)
                )
        }

    @Test
    @DisableFlags(Flags.FLAG_COMPOSE_LOCKSCREEN)
    fun testSmallClockTop_nonSplitShade_composeLockscreenOff() =
        testScope.runTest {
            assertThat(underTest.getSmallClockTopMargin(context))
                .isEqualTo(
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                        Utils.getStatusBarHeaderHeightKeyguard(context)
                )
        }
}
