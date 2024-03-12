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

package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Encapsulates business logic for device entry biometric settings. */
@ExperimentalCoroutinesApi
@SysUISingleton
class DeviceEntryBiometricSettingsInteractor
@Inject
constructor(
    repository: BiometricSettingsRepository,
) {

    /**
     * Flags that control the device entry authentication behavior.
     *
     * This exposes why biometrics may not be currently allowed.
     */
    val authenticationFlags: Flow<AuthenticationFlags> = repository.authenticationFlags

    /** Whether the current user has enrolled and enabled fingerprint auth. */
    val isFingerprintAuthEnrolledAndEnabled: Flow<Boolean> =
        repository.isFingerprintEnrolledAndEnabled

    val fingerprintAuthCurrentlyAllowed: Flow<Boolean> =
        repository.isFingerprintAuthCurrentlyAllowed
    /** Whether the current user has enrolled and enabled face auth. */
    val isFaceAuthEnrolledAndEnabled: Flow<Boolean> = repository.isFaceAuthEnrolledAndEnabled
    val faceAuthCurrentlyAllowed: Flow<Boolean> = repository.isFaceAuthCurrentlyAllowed

    /** Whether both fingerprint and face are enrolled and enabled for device entry. */
    val fingerprintAndFaceEnrolledAndEnabled: Flow<Boolean> =
        combine(
            repository.isFingerprintEnrolledAndEnabled,
            repository.isFaceAuthEnrolledAndEnabled,
        ) { fpEnabled, faceEnabled ->
            fpEnabled && faceEnabled
        }
}
