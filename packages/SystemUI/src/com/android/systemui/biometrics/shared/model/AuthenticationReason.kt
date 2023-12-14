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

package com.android.systemui.biometrics.shared.model

/**
 * The logical reason for a fingerprint auth operation if one is on-going, otherwise [NotRunning].
 */
sealed interface AuthenticationReason {
    /** Device entry requested authentication */
    data object DeviceEntryAuthentication : AuthenticationReason

    /** Settings requested authentication */
    data class SettingsAuthentication(val settingsOperation: SettingsOperations) :
        AuthenticationReason

    /** App requested authentication */
    data object BiometricPromptAuthentication : AuthenticationReason

    /** Authentication requested for other reason */
    data object OtherAuthentication : AuthenticationReason

    /** Authentication requested for unknown reason */
    data object Unknown : AuthenticationReason

    /** Authentication is not running */
    data object NotRunning : AuthenticationReason

    /** Settings operations that request biometric authentication */
    enum class SettingsOperations {
        ENROLL_ENROLLING,
        ENROLL_FIND_SENSOR,
        OTHER
    }
}
