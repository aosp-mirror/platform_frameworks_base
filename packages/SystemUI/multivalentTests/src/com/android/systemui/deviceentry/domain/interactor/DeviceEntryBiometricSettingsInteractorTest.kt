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
 */

package com.android.systemui.deviceentry.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryBiometricSettingsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository
    private val underTest = kosmos.deviceEntryBiometricSettingsInteractor
    private val testScope = kosmos.testScope

    @Test
    fun isCoex_true() = runTest {
        val isCoex by collectLastValue(underTest.fingerprintAndFaceEnrolledAndEnabled)
        biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
        assertThat(isCoex).isTrue()
    }

    @Test
    fun isCoex_faceOnly() = runTest {
        val isCoex by collectLastValue(underTest.fingerprintAndFaceEnrolledAndEnabled)
        biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
        assertThat(isCoex).isFalse()
    }

    @Test
    fun isCoex_fingerprintOnly() = runTest {
        val isCoex by collectLastValue(underTest.fingerprintAndFaceEnrolledAndEnabled)
        biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
        assertThat(isCoex).isFalse()
    }

    @Test
    fun authenticationFlags_providesAuthFlagsFromRepository() =
        testScope.runTest {
            assertThat(underTest.authenticationFlags)
                .isSameInstanceAs(biometricSettingsRepository.authenticationFlags)
        }

    @Test
    fun isFaceAuthEnrolledAndEnabled_providesValueFromRepository() =
        testScope.runTest {
            assertThat(underTest.isFaceAuthEnrolledAndEnabled)
                .isSameInstanceAs(biometricSettingsRepository.isFaceAuthEnrolledAndEnabled)
        }

    @Test
    fun isFingerprintAuthEnrolledAndEnabled_providesValueFromRepository() =
        testScope.runTest {
            assertThat(underTest.isFingerprintAuthEnrolledAndEnabled)
                .isSameInstanceAs(biometricSettingsRepository.isFingerprintEnrolledAndEnabled)
        }
}
