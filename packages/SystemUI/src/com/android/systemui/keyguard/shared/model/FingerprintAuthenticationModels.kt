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

package com.android.systemui.keyguard.shared.model

import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN
import android.hardware.fingerprint.FingerprintManager
import android.os.SystemClock.elapsedRealtime
import com.android.systemui.biometrics.shared.model.AuthenticationReason

/**
 * Fingerprint authentication status provided by
 * [com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository]
 *
 * @isEngaged whether fingerprint is actively engaged by the user. This is distinct from fingerprint
 * running on the device. Can be null if the status does not have an associated isEngaged state.
 */
sealed class FingerprintAuthenticationStatus(val isEngaged: Boolean?)

/** Fingerprint authentication success status. */
data class SuccessFingerprintAuthenticationStatus(
    val userId: Int,
    val isStrongBiometric: Boolean,
) : FingerprintAuthenticationStatus(isEngaged = false)

/** Fingerprint authentication help message. */
data class HelpFingerprintAuthenticationStatus(
    val msgId: Int,
    val msg: String?,
) : FingerprintAuthenticationStatus(isEngaged = null)

/** Fingerprint acquired message. */
data class AcquiredFingerprintAuthenticationStatus(
    val authenticationReason: AuthenticationReason,
    val acquiredInfo: Int
) :
    FingerprintAuthenticationStatus(
        isEngaged =
            if (acquiredInfo == FINGERPRINT_ACQUIRED_START) {
                true
            } else if (
                acquiredInfo == FINGERPRINT_ACQUIRED_UNKNOWN ||
                    acquiredInfo == FINGERPRINT_ACQUIRED_GOOD
            ) {
                null
            } else {
                // soft errors that indicate fingerprint activity ended
                false
            }
    ) {

    val fingerprintCaptureStarted: Boolean = acquiredInfo == FINGERPRINT_ACQUIRED_START

    val fingerprintCaptureCompleted: Boolean = acquiredInfo == FINGERPRINT_ACQUIRED_GOOD
}

/** Fingerprint authentication failed message. */
data object FailFingerprintAuthenticationStatus :
    FingerprintAuthenticationStatus(isEngaged = false)

/** Fingerprint authentication error message */
data class ErrorFingerprintAuthenticationStatus(
    val msgId: Int,
    val msg: String? = null,
    // present to break equality check if the same error occurs repeatedly.
    val createdAt: Long = elapsedRealtime(),
) : FingerprintAuthenticationStatus(isEngaged = false) {
    fun isCancellationError(): Boolean =
        msgId == BiometricFingerprintConstants.FINGERPRINT_ERROR_CANCELED ||
            msgId == BiometricFingerprintConstants.FINGERPRINT_ERROR_USER_CANCELED

    fun isPowerPressedError(): Boolean =
        msgId == BiometricFingerprintConstants.BIOMETRIC_ERROR_POWER_PRESSED

    fun isLockoutError(): Boolean =
        msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT ||
            msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT
}
