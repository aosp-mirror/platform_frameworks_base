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
 *
 */

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.FingerprintAuthenticationStatus
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

@SysUISingleton
class FakeDeviceEntryFingerprintAuthRepository @Inject constructor() :
    DeviceEntryFingerprintAuthRepository {
    private val _isLockedOut = MutableStateFlow(false)
    override val isLockedOut: StateFlow<Boolean> = _isLockedOut.asStateFlow()
    fun setLockedOut(lockedOut: Boolean) {
        _isLockedOut.value = lockedOut
    }

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: Flow<Boolean>
        get() = _isRunning

    override val isEngaged: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun setIsRunning(value: Boolean) {
        _isRunning.value = value
    }

    private var fpSensorType = MutableStateFlow<BiometricType?>(null)
    override val availableFpSensorType: Flow<BiometricType?>
        get() = fpSensorType
    fun setAvailableFpSensorType(value: BiometricType?) {
        fpSensorType.value = value
    }

    private var _authenticationStatus = MutableStateFlow<FingerprintAuthenticationStatus?>(null)
    override val authenticationStatus: Flow<FingerprintAuthenticationStatus>
        get() = _authenticationStatus.filterNotNull()

    private var _shouldUpdateIndicatorVisibility = MutableStateFlow(false)
    override val shouldUpdateIndicatorVisibility: Flow<Boolean>
        get() = _shouldUpdateIndicatorVisibility

    fun setAuthenticationStatus(status: FingerprintAuthenticationStatus) {
        _authenticationStatus.value = status
    }

    fun setShouldUpdateIndicatorVisibility(shouldUpdateIndicatorVisibility: Boolean) {
        _shouldUpdateIndicatorVisibility.value = shouldUpdateIndicatorVisibility
    }
}

@Module
interface FakeDeviceEntryFingerprintAuthRepositoryModule {
    @Binds
    fun bindFake(
        fake: FakeDeviceEntryFingerprintAuthRepository
    ): DeviceEntryFingerprintAuthRepository
}
