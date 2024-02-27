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

import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DeviceEntryFingerprintAuthInteractor
@Inject
constructor(
    repository: DeviceEntryFingerprintAuthRepository,
    biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    fingerprintPropertyRepository: FingerprintPropertyRepository,
) {
    /** Whether fingerprint authentication is currently running or not */
    val isRunning: Flow<Boolean> = repository.isRunning

    /** Provide the current status of fingerprint authentication. */
    val authenticationStatus: Flow<FingerprintAuthenticationStatus> =
        repository.authenticationStatus

    val isLockedOut: Flow<Boolean> = repository.isLockedOut

    val fingerprintFailure: Flow<FailFingerprintAuthenticationStatus> =
        repository.authenticationStatus.filterIsInstance<FailFingerprintAuthenticationStatus>()
    val fingerprintError: Flow<ErrorFingerprintAuthenticationStatus> =
        repository.authenticationStatus.filterIsInstance<ErrorFingerprintAuthenticationStatus>()
    val fingerprintHelp: Flow<HelpFingerprintAuthenticationStatus> =
        repository.authenticationStatus.filterIsInstance<HelpFingerprintAuthenticationStatus>()

    /**
     * Whether fingerprint authentication is currently allowed for the user. This is true if the
     * user has fingerprint auth enabled, enrolled, it is not disabled by any security timeouts by
     * [com.android.systemui.keyguard.shared.model.AuthenticationFlags] and not locked out due to
     * too many incorrect attempts.
     */
    val isFingerprintAuthCurrentlyAllowed: Flow<Boolean> =
        combine(isLockedOut, biometricSettingsInteractor.fingerprintAuthCurrentlyAllowed, ::Pair)
            .map { (lockedOut, currentlyAllowed) -> !lockedOut && currentlyAllowed }

    /**
     * Whether the fingerprint sensor is present under the display as opposed to being on the power
     * button or behind/rear of the phone.
     */
    val isSensorUnderDisplay =
        fingerprintPropertyRepository.sensorType.map(FingerprintSensorType::isUdfps)
}
