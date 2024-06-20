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

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryForegroundViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest: DeviceEntryForegroundViewModel =
        kosmos.deviceEntryForegroundIconViewModel

    @Test
    fun aodIconColorWhite() =
        testScope.runTest {
            val viewModel by collectLastValue(underTest.viewModel)

            givenUdfpsEnrolledAndEnabled()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
            )

            assertThat(viewModel?.useAodVariant).isEqualTo(true)
            assertThat(viewModel?.tint).isEqualTo(Color.WHITE)
        }

    @Test
    fun startsDozing_doNotShowAodVariant() =
        testScope.runTest {
            val viewModel by collectLastValue(underTest.viewModel)

            givenUdfpsEnrolledAndEnabled()
            kosmos.run {
                fakeKeyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.DOZING,
                    testScope = testScope,
                    throughTransitionState = TransitionState.STARTED,
                )
            }

            assertThat(viewModel?.useAodVariant).isEqualTo(false)
        }

    @Test
    fun finishedDozing_showAodVariant() =
        testScope.runTest {
            val viewModel by collectLastValue(underTest.viewModel)

            givenUdfpsEnrolledAndEnabled()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                testScope = testScope,
                throughTransitionState = TransitionState.FINISHED,
            )

            assertThat(viewModel?.useAodVariant).isEqualTo(true)
        }

    @Test
    fun startTransitionToLockscreenFromDozing_doNotShowAodVariant() =
        testScope.runTest {
            val viewModel by collectLastValue(underTest.viewModel)

            givenUdfpsEnrolledAndEnabled()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                testScope = testScope,
                throughTransitionState = TransitionState.FINISHED,
            )
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
                throughTransitionState = TransitionState.RUNNING,
            )

            assertThat(viewModel?.useAodVariant).isEqualTo(false)
        }

    private fun givenUdfpsEnrolledAndEnabled() {
        kosmos.fakeFingerprintPropertyRepository.supportsUdfps()
        kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
    }
}
