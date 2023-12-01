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
import com.android.systemui.deviceentry.shared.DeviceEntryBiometricMode
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.shared.model.FailedFaceAuthenticationStatus
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Business logic for device entry biometric states that may differ based on the biometric mode. */
@ExperimentalCoroutinesApi
@SysUISingleton
class DeviceEntryBiometricAuthInteractor
@Inject
constructor(
    biometricSettingsRepository: BiometricSettingsRepository,
    deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
) {
    private val biometricMode: Flow<DeviceEntryBiometricMode> =
        combine(
            biometricSettingsRepository.isFingerprintEnrolledAndEnabled,
            biometricSettingsRepository.isFaceAuthEnrolledAndEnabled,
        ) { fingerprintEnrolled, faceEnrolled ->
            if (fingerprintEnrolled && faceEnrolled) {
                DeviceEntryBiometricMode.CO_EXPERIENCE
            } else if (fingerprintEnrolled) {
                DeviceEntryBiometricMode.FINGERPRINT_ONLY
            } else if (faceEnrolled) {
                DeviceEntryBiometricMode.FACE_ONLY
            } else {
                DeviceEntryBiometricMode.NONE
            }
        }
    private val faceOnly: Flow<Boolean> =
        biometricMode.map { it == DeviceEntryBiometricMode.FACE_ONLY }

    /**
     * Triggered if face is the only biometric that can be used for device entry and a face failure
     * occurs.
     */
    val faceOnlyFaceFailure: Flow<FailedFaceAuthenticationStatus> =
        faceOnly.flatMapLatest { faceOnly ->
            if (faceOnly) {
                deviceEntryFaceAuthInteractor.faceFailure
            } else {
                emptyFlow()
            }
        }
}
