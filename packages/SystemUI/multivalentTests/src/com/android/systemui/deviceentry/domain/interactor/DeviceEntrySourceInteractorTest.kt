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

package com.android.systemui.deviceentry.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntrySourceInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val underTest = kosmos.deviceEntrySourceInteractor

    @Test
    fun deviceEntryFromFaceUnlock() =
        testScope.runTest {
            val deviceEntryFromBiometricAuthentication by
                collectLastValue(underTest.deviceEntryFromBiometricSource)
            keyguardRepository.setBiometricUnlockSource(BiometricUnlockSource.FACE_SENSOR)
            keyguardRepository.setBiometricUnlockState(BiometricUnlockModel.WAKE_AND_UNLOCK)
            runCurrent()
            assertThat(deviceEntryFromBiometricAuthentication)
                .isEqualTo(BiometricUnlockSource.FACE_SENSOR)
        }

    @Test
    fun deviceEntryFromFingerprintUnlock() = runTest {
        val deviceEntryFromBiometricAuthentication by
            collectLastValue(underTest.deviceEntryFromBiometricSource)
        keyguardRepository.setBiometricUnlockSource(BiometricUnlockSource.FINGERPRINT_SENSOR)
        keyguardRepository.setBiometricUnlockState(BiometricUnlockModel.WAKE_AND_UNLOCK)
        runCurrent()
        assertThat(deviceEntryFromBiometricAuthentication)
            .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
    }

    @Test
    fun noDeviceEntry() = runTest {
        val deviceEntryFromBiometricAuthentication by
            collectLastValue(underTest.deviceEntryFromBiometricSource)
        keyguardRepository.setBiometricUnlockSource(BiometricUnlockSource.FINGERPRINT_SENSOR)
        // doesn't dismiss keyguard:
        keyguardRepository.setBiometricUnlockState(BiometricUnlockModel.ONLY_WAKE)
        runCurrent()
        assertThat(deviceEntryFromBiometricAuthentication).isNull()
    }
}
