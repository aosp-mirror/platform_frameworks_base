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

/** Fake implementation of [DeviceEntryHapticsRepository] */
@SysUISingleton
class FakeDeviceEntryHapticsRepository @Inject constructor() : DeviceEntryHapticsRepository {
    private var _successHapticRequest: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val successHapticRequest: Flow<Boolean> = _successHapticRequest.asStateFlow()

    private var _errorHapticRequest: MutableStateFlow<Boolean> = MutableStateFlow(false)
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
interface FakeDeviceEntryHapticsRepositoryModule {
    @Binds fun bindFake(fake: FakeDeviceEntryHapticsRepository): DeviceEntryHapticsRepository
}
