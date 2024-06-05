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

import android.hardware.fingerprint.FingerprintManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.shared.model.FaceFailureMessage
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FingerprintFailureMessage
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class AlternateBouncerMessageAreaViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fingerprintAuthRepository by lazy {
        kosmos.fakeDeviceEntryFingerprintAuthRepository
    }
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val bouncerRepository by lazy { kosmos.fakeKeyguardBouncerRepository }
    private val biometricSettingsRepository by lazy { kosmos.fakeBiometricSettingsRepository }
    private val underTest: AlternateBouncerMessageAreaViewModel =
        kosmos.alternateBouncerMessageAreaViewModel

    @Before
    fun setUp() {
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
        biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
    }

    @Test
    fun noInitialValue() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            assertThat(message).isNull()
        }

    @Test
    fun fingerprintMessage() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            runCurrent()
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message).isInstanceOf(FingerprintFailureMessage::class.java)
        }

    @Test
    fun fingerprintLockoutMessage_notShown() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            runCurrent()
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ERROR_LOCKOUT,
                    msg = "test lockout",
                )
            )
            assertThat(message).isNull()
        }

    @Test
    fun alternateBouncerNotVisible_messagesNeverShow() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(false)
            runCurrent()
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message).isNull()

            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())
            assertThat(message).isNull()
        }

    @Test
    fun faceFailMessage() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            runCurrent()
            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())
            assertThat(message).isInstanceOf(FaceFailureMessage::class.java)
        }

    @Test
    fun faceThenFingerprintMessage() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            runCurrent()
            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())
            assertThat(message).isInstanceOf(FaceFailureMessage::class.java)

            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message).isInstanceOf(FingerprintFailureMessage::class.java)
        }

    @Test
    fun fingerprintMessagePreventsFaceMessageFromShowing() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            runCurrent()
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message).isInstanceOf(FingerprintFailureMessage::class.java)

            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())
            assertThat(message).isInstanceOf(FingerprintFailureMessage::class.java)
        }

    @Test
    fun fingerprintMessageAllowsFaceMessageAfter4000ms() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            bouncerRepository.setAlternateVisible(true)
            runCurrent()
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message).isInstanceOf(FingerprintFailureMessage::class.java)

            advanceTimeBy(4000)

            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())
            assertThat(message).isInstanceOf(FaceFailureMessage::class.java)
        }
}
