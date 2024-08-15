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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [DeviceEntryRepository] */
@SysUISingleton
class FakeDeviceEntryRepository @Inject constructor() : DeviceEntryRepository {

    private val _isLockscreenEnabled = MutableStateFlow(true)
    override val isLockscreenEnabled: StateFlow<Boolean> = _isLockscreenEnabled.asStateFlow()

    private val _isBypassEnabled = MutableStateFlow(false)
    override val isBypassEnabled: StateFlow<Boolean> = _isBypassEnabled

    private var pendingLockscreenEnabled = _isLockscreenEnabled.value

    override suspend fun isLockscreenEnabled(): Boolean {
        _isLockscreenEnabled.value = pendingLockscreenEnabled
        return isLockscreenEnabled.value
    }

    fun setLockscreenEnabled(isLockscreenEnabled: Boolean) {
        _isLockscreenEnabled.value = isLockscreenEnabled
        pendingLockscreenEnabled = _isLockscreenEnabled.value
    }

    fun setPendingLockscreenEnabled(isLockscreenEnabled: Boolean) {
        pendingLockscreenEnabled = isLockscreenEnabled
    }

    fun setBypassEnabled(isBypassEnabled: Boolean) {
        _isBypassEnabled.value = isBypassEnabled
    }
}

@Module
interface FakeDeviceEntryRepositoryModule {
    @Binds fun bindFake(fake: FakeDeviceEntryRepository): DeviceEntryRepository
}
