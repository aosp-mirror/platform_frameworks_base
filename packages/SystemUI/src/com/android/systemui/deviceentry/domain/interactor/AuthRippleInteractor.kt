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
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Business logic for device entry auth ripple interactions. */
@ExperimentalCoroutinesApi
@SysUISingleton
class AuthRippleInteractor
@Inject
constructor(
    deviceEntrySourceInteractor: DeviceEntrySourceInteractor,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
) {
    private val showUnlockRippleFromDeviceEntryIcon: Flow<BiometricUnlockSource> =
        deviceEntryUdfpsInteractor.isUdfpsSupported.flatMapLatest { isUdfpsSupported ->
            if (isUdfpsSupported) {
                deviceEntrySourceInteractor.deviceEntryFromDeviceEntryIcon.map {
                    BiometricUnlockSource.FINGERPRINT_SENSOR
                }
            } else {
                emptyFlow()
            }
        }

    private val showUnlockRippleFromBiometricUnlock: Flow<BiometricUnlockSource> =
        deviceEntrySourceInteractor.deviceEntryFromBiometricSource
    val showUnlockRipple: Flow<BiometricUnlockSource> =
        merge(
            showUnlockRippleFromDeviceEntryIcon,
            showUnlockRippleFromBiometricUnlock,
        )
}
