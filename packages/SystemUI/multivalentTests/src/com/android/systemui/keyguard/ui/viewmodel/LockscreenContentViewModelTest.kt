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
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.authController
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
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
            underTest = lockscreenContentViewModel
        }
    }

    @Test
    fun isUdfpsVisible_withUdfps_true() =
        with(kosmos) {
            testScope.runTest {
                whenever(kosmos.authController.isUdfpsSupported).thenReturn(true)
                assertThat(underTest.isUdfpsVisible).isTrue()
            }
        }

    @Test
    fun isUdfpsVisible_withoutUdfps_false() =
        with(kosmos) {
            testScope.runTest {
                whenever(kosmos.authController.isUdfpsSupported).thenReturn(false)
                assertThat(underTest.isUdfpsVisible).isFalse()
            }
        }

    @Test
    fun isLargeClockVisible_withLargeClock_true() =
        with(kosmos) {
            testScope.runTest {
                kosmos.fakeKeyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
                assertThat(underTest.isLargeClockVisible).isTrue()
            }
        }

    @Test
    fun isLargeClockVisible_withSmallClock_false() =
        with(kosmos) {
            testScope.runTest {
                kosmos.fakeKeyguardClockRepository.setClockSize(KeyguardClockSwitch.SMALL)
                assertThat(underTest.isLargeClockVisible).isFalse()
            }
        }

    @Test
    fun areNotificationsVisible_withSmallClock_true() =
        with(kosmos) {
            testScope.runTest {
                kosmos.fakeKeyguardClockRepository.setClockSize(KeyguardClockSwitch.SMALL)
                assertThat(underTest.areNotificationsVisible).isTrue()
            }
        }

    @Test
    fun areNotificationsVisible_withLargeClock_false() =
        with(kosmos) {
            testScope.runTest {
                kosmos.fakeKeyguardClockRepository.setClockSize(KeyguardClockSwitch.LARGE)
                assertThat(underTest.areNotificationsVisible).isFalse()
            }
        }
}
