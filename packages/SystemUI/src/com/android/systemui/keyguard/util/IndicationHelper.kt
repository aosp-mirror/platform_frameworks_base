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
 * limitations under the License
 */

package com.android.systemui.keyguard.util

import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricSourceType
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class IndicationHelper
@Inject
constructor(
    val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) {
    fun shouldSuppressErrorMsg(biometricSource: BiometricSourceType, msgId: Int): Boolean {
        return when (biometricSource) {
            BiometricSourceType.FINGERPRINT ->
                (isPrimaryAuthRequired() && !isFingerprintLockoutErrorMsg(msgId)) ||
                    msgId == BiometricFingerprintConstants.FINGERPRINT_ERROR_CANCELED ||
                    msgId == BiometricFingerprintConstants.FINGERPRINT_ERROR_USER_CANCELED ||
                    msgId == BiometricFingerprintConstants.BIOMETRIC_ERROR_POWER_PRESSED
            BiometricSourceType.FACE ->
                (isPrimaryAuthRequired() && !isFaceLockoutErrorMsg(msgId)) ||
                    msgId == BiometricFaceConstants.FACE_ERROR_CANCELED ||
                    msgId == BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS
            else -> false
        }
    }

    private fun isFingerprintLockoutErrorMsg(msgId: Int): Boolean {
        return msgId == BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT ||
            msgId == BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT
    }

    fun isFaceLockoutErrorMsg(msgId: Int): Boolean {
        return msgId == BiometricFaceConstants.FACE_ERROR_LOCKOUT ||
            msgId == BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT
    }

    private fun isPrimaryAuthRequired(): Boolean {
        // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
        // as long as primary auth, i.e. PIN/pattern/password, is required), so it's ok to
        // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
        // check of whether non-strong biometric is allowed since strong biometrics can still be
        // used.
        return !keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)
    }
}
