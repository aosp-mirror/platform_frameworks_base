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

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel.ClockLayout
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.clocks.ClockConfig
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.statusbar.ui.fakeSystemBarUtilsProxy
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class KeyguardClockViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val underTest by lazy { kosmos.keyguardClockViewModel }
    val res = context.resources

    @Mock lateinit var clockController: ClockController
    @Mock lateinit var largeClock: ClockFaceController
    @Mock lateinit var smallClock: ClockFaceController

    var config = ClockConfig("TEST", "Test", "")
    var faceConfig = ClockFaceConfig()

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(clockController.largeClock).thenReturn(largeClock)
        whenever(clockController.smallClock).thenReturn(smallClock)
        whenever(clockController.config).thenAnswer { config }
        whenever(largeClock.config).thenAnswer { faceConfig }
        whenever(smallClock.config).thenAnswer { faceConfig }
    }

    @Test
    fun currentClockLayout_splitShadeOn_clockCentered_largeClock() =
        testScope.runTest {
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)

            with(kosmos) {
                shadeRepository.setShadeLayoutWide(true)
                keyguardRepository.setClockShouldBeCentered(true)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(currentClockLayout).isEqualTo(ClockLayout.LARGE_CLOCK)
        }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun currentClockLayout_splitShadeOn_clockNotCentered_largeClock_splitShadeLargeClock() =
        testScope.runTest {
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)

            with(kosmos) {
                shadeRepository.setShadeLayoutWide(true)
                keyguardRepository.setClockShouldBeCentered(false)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(currentClockLayout).isEqualTo(ClockLayout.SPLIT_SHADE_LARGE_CLOCK)
        }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun currentClockLayout_splitShadeOn_clockNotCentered_smallClock_splitShadeSmallClock() =
        testScope.runTest {
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)

            with(kosmos) {
                shadeRepository.setShadeLayoutWide(true)
                keyguardRepository.setClockShouldBeCentered(false)
                keyguardClockRepository.setClockSize(ClockSize.SMALL)
            }

            assertThat(currentClockLayout).isEqualTo(ClockLayout.SPLIT_SHADE_SMALL_CLOCK)
        }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun currentClockLayout_singleShade_smallClock_smallClock() =
        testScope.runTest {
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)

            with(kosmos) {
                shadeRepository.setShadeLayoutWide(false)
                keyguardClockRepository.setClockSize(ClockSize.SMALL)
            }

            assertThat(currentClockLayout).isEqualTo(ClockLayout.SMALL_CLOCK)
        }

    @Test
    fun currentClockLayout_singleShade_largeClock_largeClock() =
        testScope.runTest {
            val currentClockLayout by collectLastValue(underTest.currentClockLayout)

            with(kosmos) {
                shadeRepository.setShadeLayoutWide(false)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(currentClockLayout).isEqualTo(ClockLayout.LARGE_CLOCK)
        }

    @Test
    fun hasCustomPositionUpdatedAnimation_withConfigTrue_isTrue() =
        testScope.runTest {
            val hasCustomPositionUpdatedAnimation by
                collectLastValue(underTest.hasCustomPositionUpdatedAnimation)

            with(kosmos) {
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
                faceConfig = ClockFaceConfig(hasCustomPositionUpdatedAnimation = true)
                fakeKeyguardClockRepository.setCurrentClock(clockController)
            }

            assertThat(hasCustomPositionUpdatedAnimation).isEqualTo(true)
        }

    @Test
    fun hasCustomPositionUpdatedAnimation_withConfigFalse_isFalse() =
        testScope.runTest {
            val hasCustomPositionUpdatedAnimation by
                collectLastValue(underTest.hasCustomPositionUpdatedAnimation)

            with(kosmos) {
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
                faceConfig = ClockFaceConfig(hasCustomPositionUpdatedAnimation = false)
                fakeKeyguardClockRepository.setCurrentClock(clockController)
            }

            assertThat(hasCustomPositionUpdatedAnimation).isEqualTo(false)
        }

    @Test
    fun testClockSize_alwaysSmallClockSize() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)

            with(kosmos) {
                fakeKeyguardClockRepository.setSelectedClockSize(ClockSizeSetting.SMALL)
                keyguardClockRepository.setClockSize(ClockSize.LARGE)
            }

            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun testClockSize_dynamicClockSize() =
        testScope.runTest {
            with(kosmos) {
                val value by collectLastValue(underTest.clockSize)
                fakeKeyguardClockRepository.setSelectedClockSize(ClockSizeSetting.DYNAMIC)

                keyguardClockRepository.setClockSize(ClockSize.SMALL)
                assertThat(value).isEqualTo(ClockSize.SMALL)

                keyguardClockRepository.setClockSize(ClockSize.LARGE)
                assertThat(value).isEqualTo(ClockSize.LARGE)
            }
        }

    @Test
    fun isLargeClockVisible_whenLargeClockSize_isTrue() =
        testScope.runTest {
            val value by collectLastValue(underTest.isLargeClockVisible)
            kosmos.keyguardClockRepository.setClockSize(ClockSize.LARGE)
            assertThat(value).isEqualTo(true)
        }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun isLargeClockVisible_whenSmallClockSize_isFalse() =
        testScope.runTest {
            val value by collectLastValue(underTest.isLargeClockVisible)
            kosmos.keyguardClockRepository.setClockSize(ClockSize.SMALL)
            assertThat(value).isEqualTo(false)
        }

    @Test
    @EnableSceneContainer
    fun testSmallClockTop_splitShade_sceneContainerOn() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeLayoutWide(true)
                fakeSystemBarUtilsProxy.fakeKeyguardStatusBarHeight = KEYGUARD_STATUS_BAR_HEIGHT
            }

            val expected =
                res.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin) -
                    KEYGUARD_STATUS_BAR_HEIGHT
            assertThat(underTest.getSmallClockTopMargin()).isEqualTo(expected)
        }

    @Test
    @DisableSceneContainer
    fun testSmallClockTop_splitShade_sceneContainerOff() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeLayoutWide(true)
                fakeSystemBarUtilsProxy.fakeKeyguardStatusBarHeight = KEYGUARD_STATUS_BAR_HEIGHT
            }

            assertThat(underTest.getSmallClockTopMargin())
                .isEqualTo(res.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin))
        }

    @Test
    @EnableSceneContainer
    fun testSmallClockTop_nonSplitShade_sceneContainerOn() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeLayoutWide(false)
                fakeSystemBarUtilsProxy.fakeKeyguardStatusBarHeight = KEYGUARD_STATUS_BAR_HEIGHT
            }

            assertThat(underTest.getSmallClockTopMargin())
                .isEqualTo(res.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin))
        }

    @Test
    @DisableSceneContainer
    fun testSmallClockTop_nonSplitShade_sceneContainerOff() =
        testScope.runTest {
            with(kosmos) {
                shadeRepository.setShadeLayoutWide(false)
                fakeSystemBarUtilsProxy.fakeKeyguardStatusBarHeight = KEYGUARD_STATUS_BAR_HEIGHT
            }

            val expected =
                res.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                    KEYGUARD_STATUS_BAR_HEIGHT
            assertThat(underTest.getSmallClockTopMargin()).isEqualTo(expected)
        }

    companion object {
        private const val KEYGUARD_STATUS_BAR_HEIGHT = 20

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }
}
