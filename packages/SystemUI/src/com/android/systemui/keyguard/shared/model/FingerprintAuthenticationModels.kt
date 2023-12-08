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

import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD
import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
import android.hardware.fingerprint.FingerprintManager
import android.os.SystemClock.elapsedRealtime

/**
 * Fingerprint authentication status provided by
 * [com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository]
 */
sealed class FingerprintAuthenticationStatus

/** Fingerprint authentication success status. */
data class SuccessFingerprintAuthenticationStatus(
    val userId: Int,
    val isStrongBiometric: Boolean,
) : FingerprintAuthenticationStatus()

/** Fingerprint authentication help message. */
data class HelpFingerprintAuthenticationStatus(
    val msgId: Int,
    val msg: String?,
) : FingerprintAuthenticationStatus()

/** Fingerprint acquired message. */
data class AcquiredFingerprintAuthenticationStatus(val acquiredInfo: Int) :
    FingerprintAuthenticationStatus() {

    val fingerprintCaptureStarted: Boolean = acquiredInfo == FINGERPRINT_ACQUIRED_START

    val fingerprintCaptureCompleted: Boolean = acquiredInfo == FINGERPRINT_ACQUIRED_GOOD
}

/** Fingerprint authentication failed message. */
data object FailFingerprintAuthenticationStatus : FingerprintAuthenticationStatus()

/** Fingerprint authentication error message */
data class ErrorFingerprintAuthenticationStatus(
    val msgId: Int,
    val msg: String? = null,
    // present to break equality check if the same error occurs repeatedly.
    val createdAt: Long = elapsedRealtime(),
) : FingerprintAuthenticationStatus() {
    fun isLockoutMessage(): Boolean {
        return msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT ||
            msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT
    }
}
