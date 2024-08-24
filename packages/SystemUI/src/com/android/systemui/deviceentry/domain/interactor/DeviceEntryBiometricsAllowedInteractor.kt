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

package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Individual biometrics (ie: fingerprint or face) may not be allowed to be used based on the
 * lockout states of biometrics of the same or higher sensor strength.
 *
 * This class coordinates the lockout states of each individual biometric based on the lockout
 * states of other biometrics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DeviceEntryBiometricsAllowedInteractor
@Inject
constructor(
    deviceEntryFingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    facePropertyRepository: FacePropertyRepository,
) {
    /**
     * Whether face is locked out due to too many failed face attempts. This currently includes
     * whether face is not allowed based on other biometric lockouts; however does not include if
     * face isn't allowed due to other strong or primary authentication requirements.
     */
    val isFaceLockedOut: StateFlow<Boolean> = deviceEntryFaceAuthInteractor.isLockedOut

    private val isStrongFaceAuth: Flow<Boolean> =
        facePropertyRepository.sensorInfo.map { it?.strength == SensorStrength.STRONG }

    private val isStrongFaceAuthLockedOut: Flow<Boolean> =
        combine(isStrongFaceAuth, isFaceLockedOut) { isStrongFaceAuth, isFaceAuthLockedOut ->
            isStrongFaceAuth && isFaceAuthLockedOut
        }

    /**
     * Whether fingerprint is locked out due to too many failed fingerprint attempts. This does NOT
     * include whether fingerprint is not allowed based on other biometric lockouts nor if
     * fingerprint isn't allowed due to other strong or primary authentication requirements.
     */
    val isFingerprintLockedOut: StateFlow<Boolean> =
        deviceEntryFingerprintAuthInteractor.isLockedOut

    /**
     * Whether fingerprint authentication is currently allowed for the user. This is true if the
     * user has fingerprint auth enabled, enrolled, it is not disabled by any security timeouts by
     * [com.android.systemui.keyguard.shared.model.AuthenticationFlags], not locked out due to too
     * many incorrect attempts, and other biometrics at a higher or equal strenght are not locking
     * fingerprint out.
     */
    val isFingerprintAuthCurrentlyAllowed: Flow<Boolean> =
        combine(
            isFingerprintLockedOut,
            biometricSettingsInteractor.fingerprintAuthCurrentlyAllowed,
            isStrongFaceAuthLockedOut,
        ) { fpLockedOut, fpAllowedBySettings, strongAuthFaceAuthLockedOut ->
            !fpLockedOut && fpAllowedBySettings && !strongAuthFaceAuthLockedOut
        }

    /** Whether fingerprint authentication is currently allowed while on the bouncer. */
    val isFingerprintCurrentlyAllowedOnBouncer =
        deviceEntryFingerprintAuthInteractor.isSensorUnderDisplay.flatMapLatest { sensorBelowDisplay
            ->
            if (sensorBelowDisplay) {
                flowOf(false)
            } else {
                isFingerprintAuthCurrentlyAllowed
            }
        }
}
