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

package com.android.systemui.keyguard.util

import android.hardware.biometrics.BiometricFaceConstants.BIOMETRIC_ERROR_POWER_PRESSED
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_CANCELED
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_VENDOR
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_CANCELED
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_TIMEOUT
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_USER_CANCELED
import android.hardware.biometrics.BiometricSourceType
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class IndicationHelperTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    private lateinit var underTest: IndicationHelper

    @Before
    fun setup() {
        underTest =
            IndicationHelper(
                keyguardUpdateMonitor,
            )
    }

    @Test
    fun suppressErrorMsg_faceErrorCancelled() {
        givenPrimaryAuthNotRequired()
        assertTrue(underTest.shouldSuppressErrorMsg(BiometricSourceType.FACE, FACE_ERROR_CANCELED))
    }

    @Test
    fun suppressErrorMsg_faceErrorUnableToProcess() {
        givenPrimaryAuthNotRequired()
        assertTrue(
            underTest.shouldSuppressErrorMsg(BiometricSourceType.FACE, FACE_ERROR_UNABLE_TO_PROCESS)
        )
    }

    @Test
    fun suppressErrorMsg_facePrimaryAuthRequired() {
        givenPrimaryAuthRequired()
        assertTrue(underTest.shouldSuppressErrorMsg(BiometricSourceType.FACE, FACE_ERROR_TIMEOUT))
    }

    @Test
    fun doNotSuppressErrorMsg_facePrimaryAuthRequired_faceLockout() {
        givenPrimaryAuthRequired()
        assertFalse(underTest.shouldSuppressErrorMsg(BiometricSourceType.FACE, FACE_ERROR_LOCKOUT))
        assertFalse(
            underTest.shouldSuppressErrorMsg(BiometricSourceType.FACE, FACE_ERROR_LOCKOUT_PERMANENT)
        )
    }

    @Test
    fun suppressErrorMsg_fingerprintErrorCancelled() {
        givenPrimaryAuthNotRequired()
        assertTrue(
            underTest.shouldSuppressErrorMsg(
                BiometricSourceType.FINGERPRINT,
                FINGERPRINT_ERROR_CANCELED
            )
        )
    }

    @Test
    fun suppressErrorMsg_fingerprintErrorUserCancelled() {
        givenPrimaryAuthNotRequired()
        assertTrue(
            underTest.shouldSuppressErrorMsg(
                BiometricSourceType.FINGERPRINT,
                FINGERPRINT_ERROR_USER_CANCELED
            )
        )
    }

    @Test
    fun suppressErrorMsg_fingerprintErrorPowerPressed() {
        givenPrimaryAuthNotRequired()
        assertTrue(
            underTest.shouldSuppressErrorMsg(
                BiometricSourceType.FINGERPRINT,
                BIOMETRIC_ERROR_POWER_PRESSED
            )
        )
    }

    @Test
    fun suppressErrorMsg_fingerprintPrimaryAuthRequired() {
        givenPrimaryAuthRequired()
        assertTrue(
            underTest.shouldSuppressErrorMsg(BiometricSourceType.FACE, FINGERPRINT_ERROR_TIMEOUT)
        )
    }

    @Test
    fun doNotSuppressErrorMsg_fingerprintPrimaryAuthRequired_fingerprintLockout() {
        givenPrimaryAuthRequired()
        assertFalse(
            underTest.shouldSuppressErrorMsg(
                BiometricSourceType.FINGERPRINT,
                FINGERPRINT_ERROR_LOCKOUT
            )
        )
        assertFalse(
            underTest.shouldSuppressErrorMsg(
                BiometricSourceType.FACE,
                FINGERPRINT_ERROR_LOCKOUT_PERMANENT
            )
        )
    }

    @Test
    fun isFaceLockoutErrorMsgId() {
        givenPrimaryAuthRequired()
        assertTrue(underTest.isFaceLockoutErrorMsg(FACE_ERROR_LOCKOUT))
        assertTrue(underTest.isFaceLockoutErrorMsg(FACE_ERROR_LOCKOUT_PERMANENT))
        assertFalse(underTest.isFaceLockoutErrorMsg(FACE_ERROR_TIMEOUT))
        assertFalse(underTest.isFaceLockoutErrorMsg(FACE_ERROR_CANCELED))
        assertFalse(underTest.isFaceLockoutErrorMsg(FACE_ERROR_UNABLE_TO_PROCESS))
        assertFalse(underTest.isFaceLockoutErrorMsg(FACE_ERROR_VENDOR))
    }

    private fun givenPrimaryAuthNotRequired() {
        whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
            .thenReturn(true)
    }

    private fun givenPrimaryAuthRequired() {
        whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
            .thenReturn(false)
    }
}
