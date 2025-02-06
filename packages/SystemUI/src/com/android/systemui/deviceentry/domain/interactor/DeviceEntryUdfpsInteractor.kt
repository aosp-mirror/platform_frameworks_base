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

import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.shared.customization.data.SensorLocation
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Encapsulates business logic for device entry under-display fingerprint state changes. */
@SysUISingleton
class DeviceEntryUdfpsInteractor
@Inject
constructor(
    fingerprintPropertyInteractor: FingerprintPropertyInteractor,
    // TODO (b/309655554): create & use interactors for these repositories
    fingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    biometricSettingsRepository: BiometricSettingsRepository,
) {
    /** Whether the device supports an under display fingerprint sensor. */
    val isUdfpsSupported: StateFlow<Boolean> = fingerprintPropertyInteractor.isUdfps

    /** Whether the under-display fingerprint sensor is enrolled and enabled for device entry. */
    val isUdfpsEnrolledAndEnabled: Flow<Boolean> =
        combine(isUdfpsSupported, biometricSettingsRepository.isFingerprintEnrolledAndEnabled) {
            udfps,
            fpEnrolledAndEnabled ->
            udfps && fpEnrolledAndEnabled
        }
    /** Whether the under display fingerprint sensor is currently running. */
    val isListeningForUdfps =
        isUdfpsSupported.flatMapLatest { isUdfps ->
            if (isUdfps) {
                fingerprintAuthRepository.isRunning
            } else {
                flowOf(false)
            }
        }

    /**
     * Location of the under-display fingerprint sensor on the display. Null if the device does not
     * support UDFPS.
     */
    val udfpsLocation: Flow<SensorLocation?> =
        isUdfpsSupported.flatMapLatest {
            if (it) {
                fingerprintPropertyInteractor.sensorLocation
            } else {
                flowOf(null)
            }
        }
}
