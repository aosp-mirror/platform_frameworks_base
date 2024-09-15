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
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
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
class AuthRippleInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val deviceEntrySourceInteractor = kosmos.deviceEntrySourceInteractor
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val underTest = kosmos.authRippleInteractor

    @Test
    fun enteringDeviceFromDeviceEntryIcon_udfpsNotSupported_doesNotShowAuthRipple() =
        testScope.runTest {
            val showUnlockRipple by collectLastValue(underTest.showUnlockRipple)
            fingerprintPropertyRepository.supportsRearFps()
            keyguardRepository.setKeyguardDismissible(true)
            runCurrent()
            deviceEntrySourceInteractor.attemptEnterDeviceFromDeviceEntryIcon()
            assertThat(showUnlockRipple).isNull()
        }

    @Test
    fun enteringDeviceFromDeviceEntryIcon_udfpsSupported_showsAuthRipple() =
        testScope.runTest {
            val showUnlockRipple by collectLastValue(underTest.showUnlockRipple)
            fingerprintPropertyRepository.supportsUdfps()
            keyguardRepository.setKeyguardDismissible(true)
            runCurrent()
            deviceEntrySourceInteractor.attemptEnterDeviceFromDeviceEntryIcon()
            assertThat(showUnlockRipple).isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @Test
    fun faceUnlocked_showsAuthRipple() =
        testScope.runTest {
            val showUnlockRipple by collectLastValue(underTest.showUnlockRipple)
            keyguardRepository.setBiometricUnlockState(
                BiometricUnlockMode.WAKE_AND_UNLOCK,
                BiometricUnlockSource.FACE_SENSOR
            )
            assertThat(showUnlockRipple).isEqualTo(BiometricUnlockSource.FACE_SENSOR)
        }

    @Test
    fun fingerprintUnlocked_showsAuthRipple() =
        testScope.runTest {
            val showUnlockRippleFromBiometricUnlock by collectLastValue(underTest.showUnlockRipple)
            keyguardRepository.setBiometricUnlockState(
                BiometricUnlockMode.WAKE_AND_UNLOCK,
                BiometricUnlockSource.FINGERPRINT_SENSOR
            )
            assertThat(showUnlockRippleFromBiometricUnlock)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }
}
