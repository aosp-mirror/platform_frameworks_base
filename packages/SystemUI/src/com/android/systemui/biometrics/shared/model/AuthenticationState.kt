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

import android.hardware.biometrics.BiometricSourceType

/**
 * Describes the current state of biometric authentication, including whether authentication is
 * started, stopped, or acquired and relevant parameters, and the [AuthenticationReason] for
 * authentication.
 */
sealed interface AuthenticationState {
    val requestReason: AuthenticationReason

    /**
     * Authentication started
     *
     * @param requestReason [AuthenticationReason] for starting authentication
     */
    data class AuthenticationStarted(override val requestReason: AuthenticationReason) :
        AuthenticationState

    /**
     * Authentication stopped
     *
     * @param requestReason [AuthenticationReason.NotRunning]
     */
    data class AuthenticationStopped(override val requestReason: AuthenticationReason) :
        AuthenticationState

    /**
     * Authentication acquired
     *
     * @param biometricSourceType indicates [BiometricSourceType] of acquired authentication
     * @param requestReason indicates [AuthenticationReason] for requesting auth
     * @param acquiredInfo indicates
     */
    data class AuthenticationAcquired(
        val biometricSourceType: BiometricSourceType,
        override val requestReason: AuthenticationReason,
        val acquiredInfo: Int
    ) : AuthenticationState
}
