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

package com.android.systemui.deviceentry.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Interface for classes that can access device-entry haptics application state. */
interface DeviceEntryHapticsRepository {
    /**
     * Whether a successful biometric haptic has been requested. Has not yet been handled if true.
     */
    val successHapticRequest: Flow<Boolean>

    /** Whether an error biometric haptic has been requested. Has not yet been handled if true. */
    val errorHapticRequest: Flow<Boolean>

    fun requestSuccessHaptic()
    fun handleSuccessHaptic()
    fun requestErrorHaptic()
    fun handleErrorHaptic()
}

/** Encapsulates application state for device entry haptics. */
@SysUISingleton
class DeviceEntryHapticsRepositoryImpl @Inject constructor() : DeviceEntryHapticsRepository {
    private val _successHapticRequest = MutableStateFlow(false)
    override val successHapticRequest: Flow<Boolean> = _successHapticRequest.asStateFlow()

    private val _errorHapticRequest = MutableStateFlow(false)
    override val errorHapticRequest: Flow<Boolean> = _errorHapticRequest.asStateFlow()

    override fun requestSuccessHaptic() {
        _successHapticRequest.value = true
    }

    override fun handleSuccessHaptic() {
        _successHapticRequest.value = false
    }

    override fun requestErrorHaptic() {
        _errorHapticRequest.value = true
    }

    override fun handleErrorHaptic() {
        _errorHapticRequest.value = false
    }
}

@Module
interface DeviceEntryHapticsRepositoryModule {
    @Binds fun repository(impl: DeviceEntryHapticsRepositoryImpl): DeviceEntryHapticsRepository
}
