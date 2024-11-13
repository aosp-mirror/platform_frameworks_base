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

import android.content.res.mainResources
import android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_TOO_RIGHT
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT
import android.hardware.fingerprint.FingerprintManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FaceHelpMessageDebouncer
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.domain.faceHelpMessageDeferral
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceTimeoutMessage
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FingerprintLockoutMessage
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BiometricMessageInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.biometricMessageInteractor

    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val fingerprintAuthRepository = kosmos.deviceEntryFingerprintAuthRepository
    private val faceAuthRepository = kosmos.fakeDeviceEntryFaceAuthRepository
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository
    private val faceHelpMessageDeferral = kosmos.faceHelpMessageDeferral

    @Test
    fun fingerprintErrorMessage() =
        testScope.runTest {
            val fingerprintErrorMessage by collectLastValue(underTest.fingerprintMessage)

            // GIVEN fingerprint is allowed
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            // WHEN authentication status error is FINGERPRINT_ERROR_HW_UNAVAILABLE
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    msg = "test"
                )
            )

            // THEN fingerprintErrorMessage is updated
            assertThat(fingerprintErrorMessage?.message).isEqualTo("test")
        }

    @Test
    fun fingerprintLockoutErrorMessage() =
        testScope.runTest {
            val fingerprintErrorMessage by collectLastValue(underTest.fingerprintMessage)

            // GIVEN fingerprint is allowed
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            // WHEN authentication status error is FINGERPRINT_ERROR_HW_UNAVAILABLE
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ERROR_LOCKOUT,
                    msg = "lockout"
                )
            )

            // THEN fingerprintError is updated
            assertThat(fingerprintErrorMessage).isInstanceOf(FingerprintLockoutMessage::class.java)
            assertThat(fingerprintErrorMessage?.message).isEqualTo("lockout")
        }

    @Test
    fun fingerprintErrorMessage_suppressedError() =
        testScope.runTest {
            val fingerprintErrorMessage by collectLastValue(underTest.fingerprintMessage)

            // WHEN authentication status error is FINGERPRINT_ERROR_HW_UNAVAILABLE
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    msg = "test"
                )
            )

            // THEN fingerprintErrorMessage isn't update - it's still null
            assertThat(fingerprintErrorMessage).isNull()
        }

    @Test
    fun fingerprintHelpMessage() =
        testScope.runTest {
            val fingerprintHelpMessage by collectLastValue(underTest.fingerprintMessage)

            // GIVEN fingerprint is allowed
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            // WHEN authentication status help is FINGERPRINT_ACQUIRED_IMAGER_DIRTY
            fingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ACQUIRED_IMAGER_DIRTY,
                    msg = "test"
                )
            )

            // THEN fingerprintHelpMessage is updated
            assertThat(fingerprintHelpMessage?.message).isEqualTo("test")
        }

    @Test
    fun fingerprintHelpMessage_primaryAuthRequired() =
        testScope.runTest {
            val fingerprintHelpMessage by collectLastValue(underTest.fingerprintMessage)

            // GIVEN fingerprint cannot currently be used
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(false)

            // WHEN authentication status help is FINGERPRINT_ACQUIRED_IMAGER_DIRTY
            fingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ACQUIRED_IMAGER_DIRTY,
                    msg = "test"
                )
            )

            // THEN fingerprintHelpMessage isn't update - it's still null
            assertThat(fingerprintHelpMessage).isNull()
        }

    @Test
    fun fingerprintFailMessage_nonUdfps() =
        testScope.runTest {
            val fingerprintFailMessage by collectLastValue(underTest.fingerprintMessage)

            // GIVEN rear fingerprint (not UDFPS)
            fingerprintPropertyRepository.setProperties(
                0,
                SensorStrength.STRONG,
                FingerprintSensorType.REAR,
                mapOf()
            )

            // GIVEN fingerprint is allowed
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            // WHEN authentication status fail
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // THEN fingerprintFailMessage is updated
            assertThat(fingerprintFailMessage?.message)
                .isEqualTo(
                    kosmos.mainResources.getString(
                        com.android.internal.R.string.fingerprint_error_not_match
                    )
                )
        }

    @Test
    fun fingerprintFailMessage_udfps() =
        testScope.runTest {
            val fingerprintFailMessage by collectLastValue(underTest.fingerprintMessage)

            // GIVEN UDFPS
            fingerprintPropertyRepository.setProperties(
                0,
                SensorStrength.STRONG,
                FingerprintSensorType.UDFPS_OPTICAL,
                mapOf()
            )

            // GIVEN fingerprint is allowed
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            // WHEN authentication status fail
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // THEN fingerprintFailMessage is updated to udfps message
            assertThat(fingerprintFailMessage?.message)
                .isEqualTo(
                    kosmos.mainResources.getString(
                        com.android.internal.R.string.fingerprint_udfps_error_not_match
                    )
                )
        }

    @Test
    fun faceFailedMessage_primaryAuthRequired() =
        testScope.runTest {
            val faceFailedMessage by collectLastValue(underTest.faceMessage)

            // GIVEN face is not allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(false)

            // WHEN authentication status fail
            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())

            // THEN fingerprintFailedMessage doesn't update - it's still null
            assertThat(faceFailedMessage).isNull()
        }

    @Test
    fun faceFailedMessage_faceOnly() =
        testScope.runTest {
            val faceFailedMessage by collectLastValue(underTest.faceMessage)

            // GIVEN face is allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)

            // GIVEN face only enrolled
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

            // WHEN authentication status fail
            faceAuthRepository.setAuthenticationStatus(FailedFaceAuthenticationStatus())

            // THEN fingerprintFailedMessage is updated
            assertThat(faceFailedMessage).isNotNull()
        }

    @Test
    fun faceHelpMessage_faceOnly() =
        testScope.runTest {
            val faceHelpMessage by collectLastValue(underTest.faceMessage)

            // GIVEN face is allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)

            // GIVEN face only enrolled
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

            // WHEN authentication status help past debouncer
            faceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(
                    msg = "Move left",
                    msgId = FACE_ACQUIRED_TOO_RIGHT,
                    createdAt = 0L,
                )
            )
            runCurrent()
            faceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(
                    msg = "Move left",
                    msgId = FACE_ACQUIRED_TOO_RIGHT,
                    createdAt = FaceHelpMessageDebouncer.DEFAULT_WINDOW_MS,
                )
            )

            // THEN fingerprintHelpMessage is updated
            assertThat(faceHelpMessage).isNotNull()
        }

    @Test
    fun faceHelpMessageShouldDefer() =
        testScope.runTest {
            val faceHelpMessage by collectLastValue(underTest.faceMessage)

            // GIVEN face is allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)

            // GIVEN face only enrolled
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

            // WHEN all face help messages should be deferred
            whenever(faceHelpMessageDeferral.shouldDefer(anyInt())).thenReturn(true)

            // WHEN authentication status help
            faceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(
                    msg = "Move left",
                    msgId = FACE_ACQUIRED_TOO_RIGHT,
                )
            )

            // THEN fingerprintHelpMessage is NOT updated
            assertThat(faceHelpMessage).isNull()
        }

    @Test
    fun faceHelpMessage_coEx() =
        testScope.runTest {
            val faceHelpMessage by collectLastValue(underTest.faceMessage)

            // GIVEN face and fingerprint are allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            // GIVEN face only enrolled
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)

            // WHEN authentication status help
            faceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(
                    msg = "Move left",
                    msgId = FACE_ACQUIRED_TOO_RIGHT,
                )
            )

            // THEN fingerprintHelpMessage is NOT updated
            assertThat(faceHelpMessage).isNull()
        }

    @Test
    fun faceErrorMessage_suppressedError() =
        testScope.runTest {
            val faceErrorMessage by collectLastValue(underTest.faceMessage)

            // WHEN authentication status error is FACE_ERROR_HW_UNAVAILABLE
            faceAuthRepository.setAuthenticationStatus(
                ErrorFaceAuthenticationStatus(msgId = FACE_ERROR_HW_UNAVAILABLE, msg = "test")
            )

            // THEN faceErrorMessage isn't updated - it's still null since it was suppressed
            assertThat(faceErrorMessage).isNull()
        }

    @Test
    fun faceErrorMessage() =
        testScope.runTest {
            val faceErrorMessage by collectLastValue(underTest.faceMessage)

            // WHEN authentication status error is FACE_ERROR_HW_UNAVAILABLE
            faceAuthRepository.setAuthenticationStatus(
                ErrorFaceAuthenticationStatus(msgId = FACE_ERROR_HW_UNAVAILABLE, msg = "test")
            )

            // GIVEN face is allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)

            // THEN faceErrorMessage is updated
            assertThat(faceErrorMessage?.message).isEqualTo("test")
        }

    @Test
    fun faceTimeoutErrorMessage() =
        testScope.runTest {
            val faceErrorMessage by collectLastValue(underTest.faceMessage)

            // WHEN authentication status error is FACE_ERROR_TIMEOUT
            faceAuthRepository.setAuthenticationStatus(
                ErrorFaceAuthenticationStatus(msgId = FACE_ERROR_TIMEOUT, msg = "test")
            )

            // GIVEN face is allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)

            // THEN faceErrorMessage is updated
            assertThat(faceErrorMessage).isInstanceOf(FaceTimeoutMessage::class.java)
            assertThat(faceErrorMessage?.message).isEqualTo("test")
        }

    @Test
    fun faceTimeoutDeferredErrorMessage() =
        testScope.runTest {
            whenever(faceHelpMessageDeferral.getDeferredMessage()).thenReturn("deferredMessage")
            val faceErrorMessage by collectLastValue(underTest.faceMessage)

            // WHEN authentication status error is FACE_ERROR_TIMEOUT
            faceAuthRepository.setAuthenticationStatus(
                ErrorFaceAuthenticationStatus(msgId = FACE_ERROR_TIMEOUT, msg = "test")
            )

            // GIVEN face is allowed
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)

            // THEN faceErrorMessage is updated to deferred message instead of timeout message
            assertThat(faceErrorMessage).isNotInstanceOf(FaceTimeoutMessage::class.java)
            assertThat(faceErrorMessage?.message).isEqualTo("deferredMessage")
        }
}
