/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.hardware.biometrics.BiometricSourceType.FINGERPRINT
import android.hardware.fingerprint.FingerprintManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.keyguard.util.IndicationHelper
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BiometricMessageInteractorTest : SysuiTestCase() {

    private lateinit var underTest: BiometricMessageInteractor
    private lateinit var testScope: TestScope
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var fingerprintAuthRepository: FakeDeviceEntryFingerprintAuthRepository

    @Mock private lateinit var indicationHelper: IndicationHelper
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
        fingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
        underTest =
            BiometricMessageInteractor(
                mContext.resources,
                fingerprintAuthRepository,
                fingerprintPropertyRepository,
                indicationHelper,
                keyguardUpdateMonitor,
            )
    }

    @Test
    fun fingerprintErrorMessage() =
        testScope.runTest {
            val fingerprintErrorMessage by collectLastValue(underTest.fingerprintErrorMessage)

            // GIVEN FINGERPRINT_ERROR_HW_UNAVAILABLE should NOT be suppressed
            whenever(
                    indicationHelper.shouldSuppressErrorMsg(
                        FINGERPRINT,
                        FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE
                    )
                )
                .thenReturn(false)

            // WHEN authentication status error is FINGERPRINT_ERROR_HW_UNAVAILABLE
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    msg = "test"
                )
            )

            // THEN fingerprintErrorMessage is updated
            assertThat(fingerprintErrorMessage?.source).isEqualTo(FINGERPRINT)
            assertThat(fingerprintErrorMessage?.type).isEqualTo(BiometricMessageType.ERROR)
            assertThat(fingerprintErrorMessage?.id)
                .isEqualTo(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE)
            assertThat(fingerprintErrorMessage?.message).isEqualTo("test")
        }

    @Test
    fun fingerprintErrorMessage_suppressedError() =
        testScope.runTest {
            val fingerprintErrorMessage by collectLastValue(underTest.fingerprintErrorMessage)

            // GIVEN FINGERPRINT_ERROR_HW_UNAVAILABLE should be suppressed
            whenever(
                    indicationHelper.shouldSuppressErrorMsg(
                        FINGERPRINT,
                        FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE
                    )
                )
                .thenReturn(true)

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
            val fingerprintHelpMessage by collectLastValue(underTest.fingerprintHelpMessage)

            // GIVEN primary auth is NOT required
            whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true)

            // WHEN authentication status help is FINGERPRINT_ACQUIRED_IMAGER_DIRTY
            fingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(
                    msgId = FingerprintManager.FINGERPRINT_ACQUIRED_IMAGER_DIRTY,
                    msg = "test"
                )
            )

            // THEN fingerprintHelpMessage is updated
            assertThat(fingerprintHelpMessage?.source).isEqualTo(FINGERPRINT)
            assertThat(fingerprintHelpMessage?.type).isEqualTo(BiometricMessageType.HELP)
            assertThat(fingerprintHelpMessage?.id)
                .isEqualTo(FingerprintManager.FINGERPRINT_ACQUIRED_IMAGER_DIRTY)
            assertThat(fingerprintHelpMessage?.message).isEqualTo("test")
        }

    @Test
    fun fingerprintHelpMessage_primaryAuthRequired() =
        testScope.runTest {
            val fingerprintHelpMessage by collectLastValue(underTest.fingerprintHelpMessage)

            // GIVEN primary auth is required
            whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(false)

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
            val fingerprintFailMessage by collectLastValue(underTest.fingerprintFailMessage)

            // GIVEN primary auth is NOT required
            whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true)

            // GIVEN rear fingerprint (not UDFPS)
            fingerprintPropertyRepository.setProperties(
                0,
                SensorStrength.STRONG,
                FingerprintSensorType.REAR,
                mapOf()
            )

            // WHEN authentication status fail
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // THEN fingerprintFailMessage is updated
            assertThat(fingerprintFailMessage?.source).isEqualTo(FINGERPRINT)
            assertThat(fingerprintFailMessage?.type).isEqualTo(BiometricMessageType.FAIL)
            assertThat(fingerprintFailMessage?.id)
                .isEqualTo(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED)
            assertThat(fingerprintFailMessage?.message)
                .isEqualTo(
                    mContext.resources.getString(
                        com.android.internal.R.string.fingerprint_error_not_match
                    )
                )
        }

    @Test
    fun fingerprintFailMessage_udfps() =
        testScope.runTest {
            val fingerprintFailMessage by collectLastValue(underTest.fingerprintFailMessage)

            // GIVEN primary auth is NOT required
            whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true)

            // GIVEN UDFPS
            fingerprintPropertyRepository.setProperties(
                0,
                SensorStrength.STRONG,
                FingerprintSensorType.UDFPS_OPTICAL,
                mapOf()
            )

            // WHEN authentication status fail
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // THEN fingerprintFailMessage is updated to udfps message
            assertThat(fingerprintFailMessage?.source).isEqualTo(FINGERPRINT)
            assertThat(fingerprintFailMessage?.type).isEqualTo(BiometricMessageType.FAIL)
            assertThat(fingerprintFailMessage?.id)
                .isEqualTo(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED)
            assertThat(fingerprintFailMessage?.message)
                .isEqualTo(
                    mContext.resources.getString(
                        com.android.internal.R.string.fingerprint_udfps_error_not_match
                    )
                )
        }

    @Test
    fun fingerprintFailedMessage_primaryAuthRequired() =
        testScope.runTest {
            val fingerprintFailedMessage by collectLastValue(underTest.fingerprintFailMessage)

            // GIVEN primary auth is required
            whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(false)

            // WHEN authentication status fail
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // THEN fingerprintFailedMessage isn't update - it's still null
            assertThat(fingerprintFailedMessage).isNull()
        }
}
