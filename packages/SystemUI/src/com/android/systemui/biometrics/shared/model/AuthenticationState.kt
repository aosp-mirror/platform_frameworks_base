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

package com.android.systemui.biometrics.shared.model

import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricRequestConstants
import android.hardware.biometrics.BiometricSourceType

/**
 * Describes the current state of biometric authentication, including whether authentication is
 * started, stopped, or acquired and relevant parameters, and the [AuthenticationReason] for
 * authentication.
 */
sealed interface AuthenticationState {
    /**
     * Indicates [AuthenticationReason] from [BiometricRequestConstants.RequestReason] for
     * requesting auth
     */
    val requestReason: AuthenticationReason

    /**
     * AuthenticationState when a biometric has been acquired.
     *
     * @param biometricSourceType indicates [BiometricSourceType] of acquired authentication
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for authentication
     * @param acquiredInfo [BiometricFaceConstants.FaceAcquired] or
     *   [BiometricFingerprintConstants.FingerprintAcquired] int corresponding to a known acquired
     *   message.
     */
    data class Acquired(
        val biometricSourceType: BiometricSourceType,
        override val requestReason: AuthenticationReason,
        val acquiredInfo: Int
    ) : AuthenticationState

    /**
     * AuthenticationState when an unrecoverable error is encountered during authentication.
     *
     * @param biometricSourceType identifies [BiometricSourceType] for auth error
     * @param errString authentication error string shown on the UI
     * @param errCode [BiometricFaceConstants.FaceError] or
     *   [BiometricFingerprintConstants.FingerprintError] int identifying the error message for an
     *   authentication error
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for authentication
     */
    data class Error(
        val biometricSourceType: BiometricSourceType,
        val errString: String?,
        val errCode: Int,
        override val requestReason: AuthenticationReason,
    ) : AuthenticationState

    /**
     * AuthenticationState when a biometric couldn't be authenticated.
     *
     * @param biometricSourceType identifies [BiometricSourceType] for failed auth
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for authentication
     * @param userId The user id for the requested authentication
     */
    data class Failed(
        val biometricSourceType: BiometricSourceType,
        override val requestReason: AuthenticationReason,
        val userId: Int
    ) : AuthenticationState

    /**
     * AuthenticationState when a recoverable error is encountered during authentication.
     *
     * @param biometricSourceType identifies [BiometricSourceType] for failed auth
     * @param helpString helpString guidance help string shown on the UI
     * @param helpCode An integer identifying the help message
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for authentication
     */
    data class Help(
        val biometricSourceType: BiometricSourceType,
        val helpString: String?,
        val helpCode: Int,
        override val requestReason: AuthenticationReason,
    ) : AuthenticationState

    /**
     * Authentication state when no auth is running
     *
     * @param requestReason [AuthenticationReason.NotRunning]
     */
    data class Idle(override val requestReason: AuthenticationReason) : AuthenticationState

    /**
     * AuthenticationState when auth is started
     *
     * @param biometricSourceType identifies [BiometricSourceType] for auth
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for authentication
     */
    data class Started(
        val biometricSourceType: BiometricSourceType,
        override val requestReason: AuthenticationReason
    ) : AuthenticationState

    /**
     * Authentication state when auth is stopped
     *
     * @param biometricSourceType identifies [BiometricSourceType] for auth stopped
     * @param requestReason [AuthenticationReason.NotRunning]
     */
    data class Stopped(
        val biometricSourceType: BiometricSourceType,
        override val requestReason: AuthenticationReason
    ) : AuthenticationState

    /**
     * AuthenticationState when a biometric is successfully authenticated.
     *
     * @param biometricSourceType identifies [BiometricSourceType] of successful auth
     * @param isStrongBiometric indicates whether auth was from strong biometric
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for authentication
     * @param userId The user id for the requested authentication
     */
    data class Succeeded(
        val biometricSourceType: BiometricSourceType,
        val isStrongBiometric: Boolean,
        override val requestReason: AuthenticationReason,
        val userId: Int
    ) : AuthenticationState
}
