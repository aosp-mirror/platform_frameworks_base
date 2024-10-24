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

package com.android.systemui.deviceentry.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.fingerprintPropertyInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryUdfpsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var fingerprintAuthRepository: FakeDeviceEntryFingerprintAuthRepository
    private lateinit var biometricsRepository: FakeBiometricSettingsRepository

    private lateinit var underTest: DeviceEntryUdfpsInteractor

    @Before
    fun setUp() {
        fingerprintPropertyRepository = kosmos.fakeFingerprintPropertyRepository
        fingerprintAuthRepository = kosmos.fakeDeviceEntryFingerprintAuthRepository
        biometricsRepository = kosmos.fakeBiometricSettingsRepository

        underTest =
            DeviceEntryUdfpsInteractor(
                fingerprintPropertyInteractor = kosmos.fingerprintPropertyInteractor,
                fingerprintAuthRepository = fingerprintAuthRepository,
                biometricSettingsRepository = biometricsRepository,
            )
    }

    @Test
    fun udfpsSupported_rearFp_false() =
        testScope.runTest {
            val isUdfpsSupported by collectLastValue(underTest.isUdfpsSupported)
            fingerprintPropertyRepository.supportsRearFps()
            assertThat(isUdfpsSupported).isFalse()
        }

    @Test
    fun udfpsSupoprted() =
        testScope.runTest {
            val isUdfpsSupported by collectLastValue(underTest.isUdfpsSupported)
            fingerprintPropertyRepository.supportsUdfps()
            assertThat(isUdfpsSupported).isTrue()
        }

    @Test
    fun udfpsEnrolledAndEnabled() =
        testScope.runTest {
            val isUdfpsEnrolledAndEnabled by collectLastValue(underTest.isUdfpsEnrolledAndEnabled)
            fingerprintPropertyRepository.supportsUdfps()
            biometricsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            assertThat(isUdfpsEnrolledAndEnabled).isTrue()
        }

    @Test
    fun udfpsEnrolledAndEnabled_rearFp_false() =
        testScope.runTest {
            val isUdfpsEnrolledAndEnabled by collectLastValue(underTest.isUdfpsEnrolledAndEnabled)
            fingerprintPropertyRepository.supportsRearFps()
            biometricsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            assertThat(isUdfpsEnrolledAndEnabled).isFalse()
        }

    @Test
    fun udfpsEnrolledAndEnabled_notEnrolledOrEnabled_false() =
        testScope.runTest {
            val isUdfpsEnrolledAndEnabled by collectLastValue(underTest.isUdfpsEnrolledAndEnabled)
            fingerprintPropertyRepository.supportsUdfps()
            biometricsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            assertThat(isUdfpsEnrolledAndEnabled).isFalse()
        }

    @Test
    fun isListeningForUdfps() =
        testScope.runTest {
            val isListeningForUdfps by collectLastValue(underTest.isListeningForUdfps)
            fingerprintPropertyRepository.supportsUdfps()
            fingerprintAuthRepository.setIsRunning(true)
            assertThat(isListeningForUdfps).isTrue()
        }

    @Test
    fun isListeningForUdfps_rearFp_false() =
        testScope.runTest {
            val isListeningForUdfps by collectLastValue(underTest.isListeningForUdfps)
            fingerprintPropertyRepository.supportsRearFps()
            fingerprintAuthRepository.setIsRunning(true)
            assertThat(isListeningForUdfps).isFalse()
        }

    @Test
    fun isListeningForUdfps_notRunning_false() =
        testScope.runTest {
            val isListeningForUdfps by collectLastValue(underTest.isListeningForUdfps)
            fingerprintPropertyRepository.supportsUdfps()
            fingerprintAuthRepository.setIsRunning(false)
            assertThat(isListeningForUdfps).isFalse()
        }
}
