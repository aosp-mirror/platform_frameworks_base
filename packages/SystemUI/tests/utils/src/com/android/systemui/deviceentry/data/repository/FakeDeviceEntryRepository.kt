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
    private var isLockscreenEnabled = true

    private val _isBypassEnabled = MutableStateFlow(false)
    override val isBypassEnabled: StateFlow<Boolean> = _isBypassEnabled

    private val _isUnlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    override suspend fun isLockscreenEnabled(): Boolean {
        return isLockscreenEnabled
    }

    override fun reportSuccessfulAuthentication() {
        _isUnlocked.value = true
    }

    fun setUnlocked(isUnlocked: Boolean) {
        _isUnlocked.value = isUnlocked
    }

    fun setLockscreenEnabled(isLockscreenEnabled: Boolean) {
        this.isLockscreenEnabled = isLockscreenEnabled
    }

    fun setBypassEnabled(isBypassEnabled: Boolean) {
        _isBypassEnabled.value = isBypassEnabled
    }
}

@Module
interface FakeDeviceEntryRepositoryModule {
    @Binds fun bindFake(fake: FakeDeviceEntryRepository): DeviceEntryRepository
}
