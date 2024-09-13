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
package com.android.systemui.statusbar.policy.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeDeviceProvisioningRepository @Inject constructor() : DeviceProvisioningRepository {
    private val _isDeviceProvisioned = MutableStateFlow(true)
    override val isDeviceProvisioned: Flow<Boolean> = _isDeviceProvisioned

    fun setDeviceProvisioned(isProvisioned: Boolean) {
        _isDeviceProvisioned.value = isProvisioned
    }

    override fun isDeviceProvisioned(): Boolean {
        return _isDeviceProvisioned.value
    }
}

@Module
interface FakeDeviceProvisioningRepositoryModule {
    @Binds fun bindFake(fake: FakeDeviceProvisioningRepository): DeviceProvisioningRepository
}
