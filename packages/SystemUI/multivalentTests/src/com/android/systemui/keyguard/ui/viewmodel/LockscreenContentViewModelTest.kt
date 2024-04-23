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
import com.android.systemui.biometrics.authController
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.android.systemui.unfold.fakeUnfoldTransitionProgressProvider
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenContentViewModelTest : SysuiTestCase() {

    private val kosmos: Kosmos = testKosmos()

    lateinit var underTest: LockscreenContentViewModel

    @Before
    fun setup() {
        with(kosmos) {
            fakeFeatureFlagsClassic.set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, true)
            shadeRepository.setShadeMode(ShadeMode.Single)
            underTest = lockscreenContentViewModel
        }
    }

    @Test
    fun isUdfpsVisible_withUdfps_true() =
        with(kosmos) {
            testScope.runTest {
                whenever(authController.isUdfpsSupported).thenReturn(true)
                assertThat(underTest.isUdfpsVisible).isTrue()
            }
        }

    @Test
    fun isUdfpsVisible_withoutUdfps_false() =
        with(kosmos) {
            testScope.runTest {
                whenever(authController.isUdfpsSupported).thenReturn(false)
                assertThat(underTest.isUdfpsVisible).isFalse()
            }
        }

    @Test
    fun clockSize_withLargeClock_true() =
        with(kosmos) {
            testScope.runTest {
                val clockSize by collectLastValue(underTest.clockSize)
                fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)
                assertThat(clockSize).isEqualTo(ClockSize.LARGE)
            }
        }

    @Test
    fun clockSize_withSmallClock_false() =
        with(kosmos) {
            testScope.runTest {
                val clockSize by collectLastValue(underTest.clockSize)
                fakeKeyguardClockRepository.setClockSize(ClockSize.SMALL)
                assertThat(clockSize).isEqualTo(ClockSize.SMALL)
            }
        }

    @Test
    fun areNotificationsVisible_splitShadeTrue_true() =
        with(kosmos) {
            testScope.runTest {
                val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible)
                shadeRepository.setShadeMode(ShadeMode.Split)
                fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)

                assertThat(areNotificationsVisible).isTrue()
            }
        }

    @Test
    fun areNotificationsVisible_withSmallClock_true() =
        with(kosmos) {
            testScope.runTest {
                val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible)
                fakeKeyguardClockRepository.setClockSize(ClockSize.SMALL)
                assertThat(areNotificationsVisible).isTrue()
            }
        }

    @Test
    fun areNotificationsVisible_withLargeClock_false() =
        with(kosmos) {
            testScope.runTest {
                val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible)
                fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)
                assertThat(areNotificationsVisible).isFalse()
            }
        }

    @Test
    fun shouldUseSplitNotificationShade_withConfigTrue_true() =
        with(kosmos) {
            testScope.runTest {
                val shouldUseSplitNotificationShade by
                    collectLastValue(underTest.shouldUseSplitNotificationShade)
                shadeRepository.setShadeMode(ShadeMode.Split)
                assertThat(shouldUseSplitNotificationShade).isTrue()
            }
        }

    @Test
    fun shouldUseSplitNotificationShade_withConfigFalse_false() =
        with(kosmos) {
            testScope.runTest {
                val shouldUseSplitNotificationShade by
                    collectLastValue(underTest.shouldUseSplitNotificationShade)
                shadeRepository.setShadeMode(ShadeMode.Single)
                assertThat(shouldUseSplitNotificationShade).isFalse()
            }
        }

    @Test
    fun unfoldTranslations() =
        with(kosmos) {
            testScope.runTest {
                val maxTranslation = prepareConfiguration()
                val translations by collectLastValue(underTest.unfoldTranslations)

                val unfoldProvider = fakeUnfoldTransitionProgressProvider
                unfoldProvider.onTransitionStarted()
                assertThat(translations?.start).isEqualTo(0f)
                assertThat(translations?.end).isEqualTo(-0f)

                repeat(10) { repetition ->
                    val transitionProgress = 0.1f * (repetition + 1)
                    unfoldProvider.onTransitionProgress(transitionProgress)
                    assertThat(translations?.start)
                        .isEqualTo((1 - transitionProgress) * maxTranslation)
                    assertThat(translations?.end)
                        .isEqualTo(-(1 - transitionProgress) * maxTranslation)
                }

                unfoldProvider.onTransitionFinishing()
                assertThat(translations?.start).isEqualTo(0f)
                assertThat(translations?.end).isEqualTo(-0f)

                unfoldProvider.onTransitionFinished()
                assertThat(translations?.start).isEqualTo(0f)
                assertThat(translations?.end).isEqualTo(-0f)
            }
        }

    private fun prepareConfiguration(): Int {
        val configuration = context.resources.configuration
        configuration.setLayoutDirection(Locale.US)
        kosmos.fakeConfigurationRepository.onConfigurationChange(configuration)
        val maxTranslation = 10
        kosmos.fakeConfigurationRepository.setDimensionPixelSize(
            R.dimen.notification_side_paddings,
            maxTranslation,
        )
        return maxTranslation
    }
}
